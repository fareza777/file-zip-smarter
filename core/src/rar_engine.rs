use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::extract::{entry_ratio_result, stream_entry};
use crate::model::{ArchiveInfo, Entry};
use crate::path::sanitize_entry_path;
use crate::sink::{EntryOutput, Sink};
use crate::util::{time_millis_from_dos, Control};
use crc32fast::Hasher;
use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};
use unrar::Archive;

const IN_MEMORY_LIMIT: u64 = 32 * 1024 * 1024;

pub struct RarBackend {
    pub path: PathBuf,
    pub keep_file: File,
    pub entries: Vec<Entry>,
    pub temp_dir: PathBuf,
    pub password: Option<String>,
}

pub struct RarOpen {
    pub backend: RarBackend,
    pub info: ArchiveInfo,
}

pub fn open_rar(
    path: PathBuf,
    keep_file: File,
    display_name: &str,
    archive_size: u64,
    password: Option<&str>,
    temp_dir: PathBuf,
    multipart: bool,
) -> HatchResult<RarOpen> {
    let listing = open_listing(&path, password)?;
    let solid = listing.is_solid();
    let mut entries = Vec::new();
    let mut unsafe_count = 0usize;
    let mut encrypted = 0usize;
    let mut index = 0usize;

    for item in listing {
        let header = item.map_err(map_rar)?;
        let original = header.filename.to_string_lossy().to_string();
        let sanitized = sanitize_entry_path(&original);
        if sanitized.is_none() {
            unsafe_count += 1;
        }
        if header.is_encrypted() {
            encrypted += 1;
        }
        let unsafe_path = sanitized.is_none();
        let entry = Entry {
            index,
            name: original,
            path: sanitized.unwrap_or_else(|| format!("unsafe-{index}")),
            size: header.unpacked_size,
            compressed_size: 0,
            is_directory: header.is_directory(),
            is_encrypted: header.is_encrypted(),
            is_symlink: false,
            unsafe_path,
            modified: time_millis_from_dos(header.file_time),
            method: "rar".to_string(),
            crc32: Some(header.file_crc),
            native_index: index,
        };
        entry_ratio_result(&entry)?;
        entries.push(entry);
        index += 1;
    }

    let mut info = ArchiveInfo::base("RAR", display_name, archive_size);
    info.recompute(&entries);
    info.packed_size = archive_size;
    info.solid = solid;
    info.multipart = multipart;
    info.encrypted_count = encrypted;
    if multipart {
        info.notes.push(
            "Multi-volume RAR set detected. Hatch stages the volumes in private storage and uses the bundled UnRAR engine; the extracted files still land directly in your chosen folder."
                .to_string(),
        );
    }
    if encrypted > 0 {
        info.notes.push(
            "Encrypted RAR entries need their password. Passwords stay in memory unless you explicitly save one."
                .to_string(),
        );
    }
    if unsafe_count > 0 {
        info.notes.push(format!(
            "{unsafe_count} entr{} use an unsafe path and cannot be extracted.",
            if unsafe_count == 1 { "y" } else { "ies" }
        ));
    }
    info.notes.push(
        "RAR uses the bundled UnRAR engine. Read-only extraction only; Hatch never creates RAR archives."
            .to_string(),
    );

    Ok(RarOpen {
        backend: RarBackend {
            path,
            keep_file,
            entries,
            temp_dir,
            password: password.map(str::to_string),
        },
        info,
    })
}

fn open_listing<'a>(
    path: &'a Path,
    password: Option<&'a str>,
) -> HatchResult<unrar::OpenArchive<unrar::List, unrar::CursorBeforeHeader>> {
    let result = match password {
        Some(pw) if !pw.is_empty() => Archive::with_password(path, pw).open_for_listing(),
        _ => Archive::new(path).open_for_listing(),
    };
    result.map_err(map_rar)
}

impl RarBackend {
    pub fn entries(&self) -> &[Entry] {
        &self.entries
    }

    pub fn extract_selected(
        &mut self,
        selected: &[bool],
        password: Option<&str>,
        sink: &mut dyn Sink,
        control: &Control,
    ) -> HatchResult<()> {
        let password = password.map(str::to_string).or_else(|| self.password.clone());
        let mut cursor = match password.as_deref() {
            Some(pw) if !pw.is_empty() => Archive::with_password(&self.path, pw)
                .open_for_processing()
                .map_err(map_rar)?,
            _ => Archive::new(&self.path)
                .open_for_processing()
                .map_err(map_rar)?,
        };

        let by_path: HashMap<String, usize> = self
            .entries
            .iter()
            .enumerate()
            .map(|(i, e)| (e.path.clone(), i))
            .collect();

        loop {
            let header = match cursor.read_header().map_err(map_rar)? {
                Some(h) => h,
                None => break,
            };
            // Copy metadata out before the header is consumed.
            let filename = header.entry().filename.to_string_lossy().to_string();
            let is_dir = header.entry().is_directory();
            let size = header.entry().unpacked_size;
            let crc = header.entry().file_crc;
            let sanitized = sanitize_entry_path(&filename);
            let lookup = sanitized.clone().unwrap_or_default();
            let entry_index = by_path.get(&lookup).copied();
            let selected_here = entry_index
                .and_then(|i| selected.get(i).copied())
                .unwrap_or(false);

            if !selected_here {
                cursor = header.skip().map_err(map_rar)?;
                continue;
            }
            let index = entry_index.unwrap();
            let entry = self.entries[index].clone();
            if entry.unsafe_path {
                return Err(HatchError::new(
                    ErrorKind::Unsafe,
                    format!("'{}' would be written outside the destination.", entry.name),
                ));
            }
            control.wait_if_paused(|| sink.progress(entry.index, 0, entry.size))?;

            if is_dir {
                let output = sink.open_entry(&entry)?;
                if let EntryOutput::File(_) = output {
                    // A directory mapped to a file destination is not usable.
                }
                if !sink.committed(entry.index, 0)? {
                    return Err(HatchError::new(ErrorKind::Cancelled, "Extraction stopped."));
                }
                cursor = header.skip().map_err(map_rar)?;
                continue;
            }

            // UnRAR validates the CRC of the whole file as it reads, including
            // across volumes. A split file's per-volume header carries a part
            // CRC rather than the full-file CRC, so checking it here would
            // reject perfectly good multipart sets. Let the engine decide.
            let split = header.entry().is_split_after();
            if size <= IN_MEMORY_LIMIT {
                let (data, next) = header.read().map_err(map_rar)?;
                cursor = next;
                if !split {
                    verify_rar_crc(&data, crc, &entry.name)?;
                }
                write_bytes(&data, &entry, sink, control)?;
            } else {
                let temp = unique_temp(&self.temp_dir, index);
                let next = header.extract_to(&temp).map_err(map_rar)?;
                cursor = next;
                let copy_result = (|| -> HatchResult<()> {
                    let mut temp_file = File::open(&temp)?;
                    let temp_size = temp_file.metadata()?.len();
                    if temp_size != size {
                        return Err(HatchError::corrupt(format!(
                            "'{}' is incomplete in the RAR volume set.",
                            entry.name
                        )));
                    }
                    stream_entry(
                        &mut temp_file,
                        &entry,
                        size,
                        if split { None } else { Some(crc) },
                        false,
                        sink,
                        control,
                    )?;
                    Ok(())
                })();
                let _ = std::fs::remove_file(&temp);
                copy_result?;
            }
        }
        Ok(())
    }
}

fn write_bytes(
    data: &[u8],
    entry: &Entry,
    sink: &mut dyn Sink,
    control: &Control,
) -> HatchResult<()> {
    let output = sink.open_entry(entry)?;
    let mut file = match output {
        EntryOutput::File(f) => f,
        EntryOutput::Directory => return Ok(()),
        EntryOutput::Skip => return Ok(()),
    };
    let mut written = 0usize;
    for chunk in data.chunks(1024 * 1024) {
        control.wait_if_paused(|| sink.progress(entry.index, written as u64, data.len() as u64))?;
        file.write_all(chunk)?;
        written += chunk.len();
        if !sink.progress(entry.index, written as u64, data.len() as u64)? {
            return Err(HatchError::new(ErrorKind::Cancelled, "Extraction cancelled."));
        }
    }
    file.flush()?;
    drop(file);
    if !sink.committed(entry.index, data.len() as u64)? {
        return Err(HatchError::new(ErrorKind::Cancelled, "Extraction stopped."));
    }
    Ok(())
}

fn verify_rar_crc(data: &[u8], expected: u32, name: &str) -> HatchResult<()> {
    if expected == 0 {
        return Ok(());
    }
    let mut hasher = Hasher::new();
    hasher.update(data);
    if hasher.finalize() != expected {
        return Err(HatchError::corrupt(format!(
            "Checksum mismatch on '{name}'. The RAR volume set may be incomplete."
        )));
    }
    Ok(())
}

fn unique_temp(dir: &Path, index: usize) -> PathBuf {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    dir.join(format!("hatch-rar-{index}-{stamp}"))
}

fn map_rar(err: unrar::error::UnrarError) -> HatchError {
    let lower = err.to_string().to_lowercase();
    if lower.contains("password") || lower.contains("encrypted") || lower.contains("crypt") {
        if lower.contains("wrong") {
            return HatchError::wrong_password();
        }
        return HatchError::password_required();
    }
    HatchError::from(err)
}
