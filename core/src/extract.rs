use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::model::Entry;
use crate::sink::{EntryOutput, Sink};
use crate::util::Control;
use crc32fast::Hasher;
use std::io::Read;
use std::time::{Duration, Instant};

pub const CHUNK: usize = 1024 * 1024;

pub fn entry_ratio_result(entry: &Entry) -> HatchResult<()> {
    if entry.is_directory {
        return Ok(());
    }
    if entry.size > 4_000_000_000_000 {
        return Err(HatchError::new(
            ErrorKind::Unsafe,
            "An entry declares an implausibly large size; extraction was blocked.",
        ));
    }
    if entry.compressed_size > 0 && entry.size > 512 * 1024 * 1024 {
        let ratio = entry.size / entry.compressed_size.max(1);
        if ratio > 5_000 {
            return Err(HatchError::new(
                ErrorKind::Unsafe,
                "This looks like a decompression bomb. Extraction was blocked to protect your storage.",
            ));
        }
    }
    Ok(())
}

/// Streams one archive entry to the destination chosen by the sink. The
/// declared size and CRC are verified after writing. In reclaim mode the file
/// is fsynced before the caller is allowed to remove archive bytes.
pub fn stream_entry(
    reader: &mut dyn Read,
    entry: &Entry,
    expected_size: u64,
    crc: Option<u32>,
    durable: bool,
    sink: &mut dyn Sink,
    control: &Control,
) -> HatchResult<u64> {
    entry_ratio_result(entry)?;
    let output = sink.open_entry(entry)?;
    let mut file = match output {
        EntryOutput::Directory => {
            if !sink.committed(entry.index, 0)? {
                return Err(HatchError::new(ErrorKind::Cancelled, "Extraction stopped."));
            }
            return Ok(0);
        }
        EntryOutput::Skip => return Ok(0),
        EntryOutput::File(file) => file,
    };

    let mut hasher = Hasher::new();
    let mut buf = vec![0u8; CHUNK];
    let mut written = 0u64;
    let mut last_tick = Instant::now() - Duration::from_secs(2);

    loop {
        control.wait_if_paused(|| sink.progress(entry.index, written, expected_size))?;
        if control.is_cancelled() {
            return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
        }
        let read = match reader.read(&mut buf) {
            Ok(n) => n,
            Err(err) => {
                return Err(HatchError::new(
                    ErrorKind::Corrupt,
                    format!("Could not read '{}': {err}", entry.name),
                ))
            }
        };
        if read == 0 {
            break;
        }
        std::io::Write::write_all(&mut file, &buf[..read])?;
        hasher.update(&buf[..read]);
        written += read as u64;
        if expected_size > 0 && written > expected_size.saturating_add(1 << 20) {
            return Err(HatchError::corrupt(format!(
                "'{}' expanded past its declared size. Extraction stopped.",
                entry.name
            )));
        }
        if last_tick.elapsed() >= Duration::from_millis(220) {
            last_tick = Instant::now();
            if !sink.progress(entry.index, written, expected_size)? {
                control.set_cancelled(true);
                return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
            }
        }
    }

    std::io::Write::flush(&mut file)?;
    if durable {
        file.sync_all()?;
    }
    drop(file);

    if expected_size > 0 && written != expected_size {
        return Err(HatchError::corrupt(format!(
            "'{}' is incomplete ({} of {} bytes). The archive may be damaged.",
            entry.name, written, expected_size
        )));
    }
    if let Some(expected) = crc {
        let actual = hasher.finalize();
        if actual != expected {
            return Err(HatchError::corrupt(format!(
                "Checksum mismatch on '{}'. Nothing after it was removed.",
                entry.name
            )));
        }
    }

    if !sink.progress(entry.index, written, expected_size)? {
        return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
    }
    if !sink.committed(entry.index, written)? {
        return Err(HatchError::new(ErrorKind::Cancelled, "Extraction stopped."));
    }
    Ok(written)
}

/// Streams a directory marker or empty file through the sink.
pub fn commit_directory(entry: &Entry, sink: &mut dyn Sink) -> HatchResult<()> {
    match sink.open_entry(entry)? {
        EntryOutput::Skip => Ok(()),
        _ => {
            if sink.committed(entry.index, 0)? {
                Ok(())
            } else {
                Err(HatchError::new(ErrorKind::Cancelled, "Extraction stopped."))
            }
        }
    }
}
