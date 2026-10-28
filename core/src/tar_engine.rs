use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::extract::{commit_directory, entry_ratio_result, stream_entry};
use crate::model::{ArchiveInfo, Entry};
use crate::path::sanitize_entry_path;
use crate::sink::Sink;
use crate::util::Control;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use tar::Archive;

pub struct TarBackend {
    pub file: File,
    pub entries: Vec<Entry>,
    pub temp_path: Option<std::path::PathBuf>,
}

pub struct TarOpen {
    pub backend: TarBackend,
    pub info: ArchiveInfo,
}

pub fn open_tar(
    mut file: File,
    display_name: &str,
    archive_size: u64,
    temp_path: Option<std::path::PathBuf>,
) -> HatchResult<TarOpen> {
    file.seek(SeekFrom::Start(0))?;
    let mut archive = Archive::new(&mut file);
    let mut entries = Vec::new();
    let mut unsafe_count = 0;
    let mut links = 0;
    let mut index = 0usize;
    let mut raw_index = 0usize;

    let raw_entries = archive
        .entries()
        .map_err(|e| HatchError::corrupt(format!("This TAR cannot be read: {e}")))?;
    for raw in raw_entries {
        let raw = raw.map_err(|e| {
            HatchError::corrupt(format!("The TAR header is damaged or incomplete: {e}"))
        })?;
        let header = raw.header();
        let size = header.size().unwrap_or(0);
        let kind = header.entry_type();
        let is_directory = kind.is_dir();
        let is_link = kind.is_symlink() || kind.is_hard_link();
        let path_bytes = raw.path_bytes();
        let original = String::from_utf8_lossy(&path_bytes).to_string();
        // TARs created with `tar -C dir .` carry a root "./" directory entry.
        // It has no payload and is not an unsafe path; listing it as a folder
        // named "." only confuses the file list. Skip it but keep the raw
        // counter so extraction can still walk the sequential stream.
        if is_directory && matches!(original.as_str(), "" | "." | "./") {
            raw_index += 1;
            continue;
        }
        let sanitized = sanitize_entry_path(&original);
        if sanitized.is_none() {
            unsafe_count += 1;
        }
        if is_link {
            links += 1;
        }
        let unsafe_path = sanitized.is_none();
        let entry = Entry {
            index,
            name: original,
            path: sanitized.unwrap_or_else(|| format!("unsafe-{index}")),
            size,
            compressed_size: size,
            is_directory,
            is_encrypted: false,
            is_symlink: is_link,
            unsafe_path,
            modified: header
                .mtime()
                .ok()
                .map(|secs| (secs as i64) * 1000)
                .unwrap_or(0),
            method: "tar".to_string(),
            crc32: None,
            native_index: raw_index,
        };
        entry_ratio_result(&entry)?;
        entries.push(entry);
        index += 1;
        raw_index += 1;
    }

    let mut info = ArchiveInfo::base(
        if temp_path.is_some() { "TAR.GZ" } else { "TAR" },
        display_name,
        archive_size,
    );
    info.recompute(&entries);
    if temp_path.is_some() {
        info.notes.push(
            "TAR.GZ is expanded once to a private temporary TAR so entries can be read quickly. The temporary TAR is deleted when this archive is closed."
                .to_string(),
        );
    }
    if links > 0 {
        info.notes.push(format!(
            "{links} link entr{} will be skipped for safety.",
            if links == 1 { "y" } else { "ies" }
        ));
    }
    if unsafe_count > 0 {
        info.notes.push(format!(
            "{unsafe_count} entr{} use an unsafe path and cannot be extracted.",
            if unsafe_count == 1 { "y" } else { "ies" }
        ));
    }

    Ok(TarOpen {
        backend: TarBackend {
            file,
            entries,
            temp_path,
        },
        info,
    })
}

impl TarBackend {
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
        self.file.seek(SeekFrom::Start(0))?;
        let mut archive = Archive::new(&mut self.file);
        let raw_entries = archive
            .entries()
            .map_err(|e| HatchError::corrupt(format!("This TAR cannot be read: {e}")))?;
        let mut list_index = 0usize;
        for (raw_index, raw) in raw_entries.enumerate() {
            let mut raw = raw.map_err(|e| HatchError::corrupt(e.to_string()))?;
            // Skipped root entries make raw and list positions diverge; align
            // them through native_index and drain the payload of skipped ones.
            let entry = self
                .entries
                .get(list_index)
                .filter(|entry| entry.native_index == raw_index);
            let Some(entry) = entry else {
                std::io::copy(&mut raw, &mut std::io::sink())?;
                continue;
            };
            list_index += 1;
            let selected_here = selected.get(entry.index).copied().unwrap_or(false);
            if !selected_here {
                // The tar stream is sequential; consume skipped bytes so the
                // next header can be found.
                std::io::copy(&mut raw, &mut std::io::sink())?;
                continue;
            }
            if entry.unsafe_path {
                return Err(HatchError::new(
                    ErrorKind::Unsafe,
                    format!("'{}' would be written outside the destination.", entry.name),
                ));
            }
            if entry.is_symlink {
                continue;
            }
            control.wait_if_paused(|| sink.progress(entry.index, 0, entry.size))?;
            if entry.is_directory {
                commit_directory(entry, sink)?;
                continue;
            }
            stream_entry(&mut raw, entry, entry.size, None, false, sink, control)?;
        }
        Ok(())
    }
}

/// Returns true if the first TAR header looks structurally valid.
pub fn looks_like_tar(file: &mut File) -> bool {
    let mut header = [0u8; 512];
    if file.seek(SeekFrom::Start(0)).is_err() {
        return false;
    }
    if file.read_exact(&mut header).is_err() {
        return false;
    }
    let _ = file.seek(SeekFrom::Start(0));
    let magic = &header[257..262];
    if magic == b"ustar" {
        return true;
    }
    // Old tar has no magic but has a valid checksum and a plausible name.
    let name_ok = header[..100].iter().any(|b| *b != 0);
    let checksum_ok = tar_checksum(&header).is_some();
    name_ok && checksum_ok
}

fn tar_checksum(header: &[u8; 512]) -> Option<u64> {
    let stored = std::str::from_utf8(&header[148..156]).ok()?;
    let stored = stored.trim_matches(|c: char| c == '\0' || c == ' ');
    let stored = u64::from_str_radix(stored, 8).ok()?;
    let mut sum = 0u64;
    for (i, b) in header.iter().enumerate() {
        sum += if (148..156).contains(&i) { b' ' as u64 } else { *b as u64 };
    }
    if sum == stored {
        Some(sum)
    } else {
        None
    }
}
