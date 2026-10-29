use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::extract::stream_entry;
use crate::gzip_engine::temp_name;
use crate::model::{ArchiveInfo, Entry};
use crate::path::safe_display_name;
use crate::sink::Sink;
use crate::util::Control;
use bzip2_rs::DecoderReader;
use lzma_rust2::XzReader;
use ruzstd::decoding::StreamingDecoder;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CompressionKind {
    Xz,
    Zstd,
    Bzip2,
}

impl CompressionKind {
    fn label(self) -> &'static str {
        match self {
            CompressionKind::Xz => "XZ",
            CompressionKind::Zstd => "ZSTD",
            CompressionKind::Bzip2 => "BZIP2",
        }
    }

    fn method(self) -> &'static str {
        match self {
            CompressionKind::Xz => "xz",
            CompressionKind::Zstd => "zstd",
            CompressionKind::Bzip2 => "bzip2",
        }
    }

    fn extension(self) -> &'static [&'static str] {
        match self {
            CompressionKind::Xz => &[".tar.xz", ".txz", ".xz"],
            CompressionKind::Zstd => &[".tar.zst", ".tzst", ".tar.zstd", ".zstd", ".zst"],
            CompressionKind::Bzip2 => &[".tar.bz2", ".tbz2", ".tbz", ".bz2"],
        }
    }

    fn temp_suffix(self) -> &'static str {
        match self {
            CompressionKind::Xz => "xz",
            CompressionKind::Zstd => "zst",
            CompressionKind::Bzip2 => "bz2",
        }
    }
}

pub struct CompressedBackend {
    pub file: File,
    pub entry: Entry,
    pub kind: CompressionKind,
}

pub struct CompressedOpen {
    pub backend: CompressedBackend,
    pub info: ArchiveInfo,
}

/// Opens a single compressed file (`.xz`, `.zst`, `.bz2`). The stream is
/// decoded once without writing anything to measure the real uncompressed
/// size, then decoded again into the destination on extraction. This keeps
/// Hatch's low-space promise: no private copy of the payload is created.
pub fn open_compressed(
    mut file: File,
    display_name: &str,
    archive_size: u64,
    kind: CompressionKind,
) -> HatchResult<CompressedOpen> {
    let output_name = output_name(display_name, kind);
    let mut counter = CountingSink::default();
    copy_decompressed(&mut file, kind, &mut counter)?;
    let size = counter.bytes;

    let entry = Entry {
        index: 0,
        name: output_name.clone(),
        path: output_name,
        size,
        compressed_size: archive_size,
        is_directory: false,
        is_encrypted: false,
        is_symlink: false,
        unsafe_path: false,
        modified: 0,
        method: kind.method().to_string(),
        crc32: None,
        native_index: 0,
    };
    let mut info = ArchiveInfo::base(kind.label(), display_name, archive_size);
    info.recompute(std::slice::from_ref(&entry));
    info.notes.push(format!(
        "{} stores one file and no name; the output name comes from the archive. Hatch reads the stream once to measure it, then once more to write it, so no temporary copy is needed.",
        kind.label()
    ));

    Ok(CompressedOpen {
        backend: CompressedBackend { file, entry, kind },
        info,
    })
}

/// Expands a compressed TAR into a private temporary TAR so its entry list can
/// be read with random access. The temporary file is removed with the session.
pub fn open_tar_compressed(
    mut file: File,
    _display_name: &str,
    _archive_size: u64,
    temp_dir: &Path,
    kind: CompressionKind,
) -> HatchResult<(File, PathBuf)> {
    std::fs::create_dir_all(temp_dir)?;
    let temp = temp_name(temp_dir).with_extension(kind.temp_suffix());
    let result = (|| -> HatchResult<()> {
        file.seek(SeekFrom::Start(0))?;
        let mut out = File::create(&temp)?;
        copy_decompressed(&mut file, kind, &mut out)?;
        out.flush()?;
        out.sync_all()?;
        Ok(())
    })();
    if let Err(err) = result {
        let _ = std::fs::remove_file(&temp);
        return Err(err);
    }
    let reopened = File::open(&temp)?;
    Ok((reopened, temp))
}

impl CompressedBackend {
    pub fn entries(&self) -> &[Entry] {
        std::slice::from_ref(&self.entry)
    }

    pub fn extract_selected(
        &mut self,
        selected: &[bool],
        _password: Option<&str>,
        sink: &mut dyn Sink,
        control: &Control,
    ) -> HatchResult<()> {
        if !selected.first().copied().unwrap_or(false) {
            return Ok(());
        }
        self.file.seek(SeekFrom::Start(0))?;
        let mut reader = reader_for(&mut self.file, self.kind)?;
        stream_entry(
            &mut reader,
            &self.entry,
            self.entry.size,
            None,
            false,
            sink,
            control,
        )?;
        Ok(())
    }
}

fn reader_for(file: &mut File, kind: CompressionKind) -> HatchResult<Box<dyn Read + '_>> {
    match kind {
        CompressionKind::Xz => Ok(Box::new(XzReader::new(file, true))),
        CompressionKind::Zstd => StreamingDecoder::new(file)
            .map(|reader| Box::new(reader) as Box<dyn Read>)
            .map_err(|err| {
                HatchError::corrupt(format!("This Zstandard stream cannot be read: {err}"))
            }),
        CompressionKind::Bzip2 => Ok(Box::new(DecoderReader::new(file))),
    }
}

fn copy_decompressed(
    file: &mut File,
    kind: CompressionKind,
    output: &mut dyn Write,
) -> HatchResult<u64> {
    file.seek(SeekFrom::Start(0))?;
    let mut reader = reader_for(file, kind)?;
    let mut buf = vec![0u8; 1024 * 1024];
    let mut written = 0u64;
    loop {
        let read = reader.read(&mut buf).map_err(|err| {
            HatchError::corrupt(format!(
                "This {} stream is damaged or incomplete: {err}",
                kind.label()
            ))
        })?;
        if read == 0 {
            break;
        }
        output.write_all(&buf[..read])?;
        written = written.saturating_add(read as u64);
        if written > 4_000_000_000_000 {
            return Err(HatchError::new(
                ErrorKind::Unsafe,
                "This compressed file expands beyond Hatch's safety limit.",
            ));
        }
    }
    Ok(written)
}

fn output_name(display_name: &str, kind: CompressionKind) -> String {
    let base = display_name.rsplit('/').next().unwrap_or(display_name);
    let lower = base.to_lowercase();
    for ext in kind.extension() {
        if lower.ends_with(ext) {
            let stem = &base[..base.len() - ext.len()];
            if !stem.is_empty() {
                return safe_display_name(stem);
            }
        }
    }
    safe_display_name(base)
}

#[derive(Default)]
struct CountingSink {
    bytes: u64,
}

impl Write for CountingSink {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.bytes = self.bytes.saturating_add(buf.len() as u64);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
