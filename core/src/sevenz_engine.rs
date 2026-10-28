use crate::error::{HatchError, HatchResult};
use crate::extract::{commit_directory, entry_ratio_result, stream_entry};
use crate::model::{ArchiveInfo, Entry};
use crate::path::sanitize_entry_path;
use crate::sink::Sink;
use crate::util::Control;
use sevenz_rust2::{ArchiveReader, Password};
use std::fs::File;
use std::time::{SystemTime, UNIX_EPOCH};

pub struct SevenZBackend {
    pub reader: ArchiveReader<File>,
    pub entries: Vec<Entry>,
}

pub struct SevenZOpen {
    pub backend: SevenZBackend,
    pub info: ArchiveInfo,
}

pub fn open_7z(
    file: File,
    display_name: &str,
    archive_size: u64,
    password: Option<&str>,
) -> HatchResult<SevenZOpen> {
    let password: Password = password.unwrap_or("").into();
    let mut reader = ArchiveReader::new(file, password).map_err(map_sevenz_password)?;
    reader.set_thread_count(2);
    let archive = reader.archive();
    if archive.files.len() > 2_000_000 {
        return Err(HatchError::unsupported(
            "This archive has more than two million entries, which is beyond Hatch's safety limit.",
        ));
    }

    // Callback order matters for 7z: blocks are decoded in order, then files
    // without a stream. Build the listing in exactly that order.
    let mut order = Vec::with_capacity(archive.files.len());
    let mut seen = vec![false; archive.files.len()];
    for (block_index, _) in archive.blocks.iter().enumerate() {
        let start = archive
            .stream_map
            .block_first_file_index
            .get(block_index)
            .copied()
            .unwrap_or(archive.files.len());
        let end = archive
            .stream_map
            .block_first_file_index
            .get(block_index + 1)
            .copied()
            .unwrap_or(archive.files.len());
        for i in start..end.min(archive.files.len()) {
            if !seen[i] {
                seen[i] = true;
                order.push(i);
            }
        }
    }
    for i in 0..archive.files.len() {
        if !seen[i] && archive.stream_map.file_block_index[i].is_none() {
            seen[i] = true;
            order.push(i);
        }
    }
    for i in 0..archive.files.len() {
        if !seen[i] {
            order.push(i);
        }
    }

    let mut entries = Vec::with_capacity(order.len());
    let mut unsafe_count = 0usize;
    for (index, &file_index) in order.iter().enumerate() {
        let f = &archive.files[file_index];
        let sanitized = sanitize_entry_path(&f.name);
        if sanitized.is_none() {
            unsafe_count += 1;
        }
        let modified = {
            let time: SystemTime = f.last_modified_date.into();
            time.duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0)
        };
        let unsafe_path = sanitized.is_none();
        let entry = Entry {
            index,
            name: f.name.clone(),
            path: sanitized.unwrap_or_else(|| format!("unsafe-{index}")),
            size: f.size,
            compressed_size: f.compressed_size,
            is_directory: f.is_directory,
            is_encrypted: false,
            is_symlink: false,
            unsafe_path,
            modified,
            method: "7z".to_string(),
            crc32: if f.has_crc { Some(f.crc as u32) } else { None },
            native_index: file_index,
        };
        entry_ratio_result(&entry)?;
        entries.push(entry);
    }

    let solid = archive.is_solid;
    let mut info = ArchiveInfo::base("7Z", display_name, archive_size);
    info.recompute(&entries);
    info.packed_size = archive_size;
    info.solid = solid;
    if solid {
        info.notes.push(
            "This is a solid 7z archive: entries share compressed blocks, so extraction reads one block in order. Files still land directly in your destination."
                .to_string(),
        );
    } else {
        info.notes.push(
            "7z entries are decoded in one ordered pass; Hatch writes each file directly to its destination."
                .to_string(),
        );
    }
    if unsafe_count > 0 {
        info.notes.push(format!(
            "{unsafe_count} entr{} use an unsafe path and cannot be extracted.",
            if unsafe_count == 1 { "y" } else { "ies" }
        ));
    }

    Ok(SevenZOpen {
        backend: SevenZBackend { reader, entries },
        info,
    })
}

impl SevenZBackend {
    pub fn entries(&self) -> &[Entry] {
        &self.entries
    }

    pub fn extract_selected(
        &mut self,
        selected: &[bool],
        _password: Option<&str>,
        sink: &mut dyn Sink,
        control: &Control,
    ) -> HatchResult<()> {
        let SevenZBackend { reader, entries } = self;
        let mut counter = 0usize;
        let mut captured: Option<HatchError> = None;

        let result = reader.for_each_entries(|_entry, input| {
            if captured.is_some() {
                return Ok(false);
            }
            if counter >= entries.len() {
                let _ = std::io::copy(input, &mut std::io::sink());
                return Ok(false);
            }
            let index = counter;
            counter += 1;
            let entry = entries[index].clone();
            let selected_here = selected.get(index).copied().unwrap_or(false);

            if !selected_here || entry.unsafe_path || entry.is_symlink {
                if std::io::copy(input, &mut std::io::sink()).is_err() {
                    captured = Some(HatchError::corrupt(
                        "The 7z stream could not be decoded. The archive may be damaged.",
                    ));
                    return Ok(false);
                }
                return Ok(true);
            }

            let outcome = if entry.is_directory {
                commit_directory(&entry, sink)
            } else {
                stream_entry(
                    input,
                    &entry,
                    entry.size,
                    entry.crc32,
                    false,
                    sink,
                    control,
                )
                .map(|_| ())
            };
            match outcome {
                Ok(()) => Ok(true),
                Err(err) => {
                    captured = Some(err);
                    Ok(false)
                }
            }
        });

        if let Some(err) = captured {
            return Err(err);
        }
        result.map_err(|e| {
            let lower = e.to_string().to_lowercase();
            if lower.contains("password") || lower.contains("aes") {
                if lower.contains("wrong") || lower.contains("crc") {
                    HatchError::wrong_password()
                } else {
                    HatchError::password_required()
                }
            } else {
                HatchError::from(e)
            }
        })?;
        Ok(())
    }
}

fn map_sevenz_password(err: sevenz_rust2::Error) -> HatchError {
    let lower = err.to_string().to_lowercase();
    if lower.contains("password") || lower.contains("aes") {
        if lower.contains("wrong") || lower.contains("crc") || lower.contains("check") {
            return HatchError::wrong_password();
        }
        return HatchError::password_required();
    }
    HatchError::from(err)
}
