use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::extract::{commit_directory, entry_ratio_result, stream_entry};
use crate::model::{ArchiveInfo, Entry, ReclaimEligibility};
use crate::path::sanitize_entry_path;
use crate::sink::Sink;
use crate::util::{zip_time_millis, Control};
use std::collections::HashSet;
use std::fs::File;
use zip::read::ZipArchive;

pub struct ZipBackend {
    pub zip: ZipArchive<File>,
    pub entries: Vec<Entry>,
}

pub struct ZipOpen {
    pub backend: ZipBackend,
    pub info: ArchiveInfo,
}

pub fn open_zip(file: File, display_name: &str, archive_size: u64) -> HatchResult<ZipOpen> {
    let mut zip = ZipArchive::new(file)?;
    let len = zip.len();
    if len > 2_000_000 {
        return Err(HatchError::unsupported(
            "This archive has more than two million entries, which is beyond Hatch's safety limit.",
        ));
    }

    let mut entries = Vec::with_capacity(len);
    let mut unsafe_count = 0usize;
    let mut symlink_count = 0usize;
    let mut encrypted_count = 0usize;
    let mut unsupported_methods = HashSet::new();
    let mut duplicate = false;
    let mut seen = HashSet::new();

    for i in 0..len {
        let raw = zip.by_index_raw(i)?;
        let original = raw.name().unwrap_or_default().to_string();
        let sanitized = sanitize_entry_path(&original);
        let is_directory = raw.is_dir();
        let is_symlink = raw.is_symlink();
        let encrypted = raw.encrypted();
        let method = method_name(raw.compression());
        if !matches!(
            raw.compression(),
            zip::CompressionMethod::Stored | zip::CompressionMethod::Deflated
        ) {
            unsupported_methods.insert(method.clone());
        }
        if sanitized.is_none() {
            unsafe_count += 1;
        }
        if is_symlink {
            symlink_count += 1;
        }
        if encrypted {
            encrypted_count += 1;
        }
        let unsafe_path = sanitized.is_none();
        let path = sanitized.unwrap_or_else(|| format!("unsafe-{i}"));
        if !is_directory && !is_symlink {
            if !seen.insert(path.to_lowercase()) {
                duplicate = true;
            }
        }
        let entry = Entry {
            index: i,
            name: original,
            path,
            size: raw.size(),
            compressed_size: raw.compressed_size(),
            is_directory,
            is_encrypted: encrypted,
            is_symlink,
            unsafe_path,
            modified: raw.last_modified().map(zip_time_millis).unwrap_or(0),
            method,
            crc32: Some(raw.crc32()),
            native_index: i,
        };
        entry_ratio_result(&entry)?;
        entries.push(entry);
    }

    let mut info = ArchiveInfo::base("ZIP", display_name, archive_size);
    info.recompute(&entries);
    info.encrypted_count = encrypted_count;
    if symlink_count > 0 {
        info.notes.push(format!(
            "{symlink_count} symbolic link{} will be skipped for safety.",
            if symlink_count == 1 { "" } else { "s" }
        ));
    }
    if unsafe_count > 0 {
        info.notes.push(format!(
            "{unsafe_count} entr{} use an unsafe path and cannot be extracted.",
            if unsafe_count == 1 { "y" } else { "ies" }
        ));
    }
    if !unsupported_methods.is_empty() {
        let list = unsupported_methods.into_iter().collect::<Vec<_>>().join(", ");
        info.notes.push(format!(
            "Some entries use compression Hatch cannot decode here ({list})."
        ));
    }

    let overlaps = zip.has_overlapping_entries().unwrap_or(true);
    let not_plain = entries.iter().any(|e| {
        !e.is_directory && !e.is_symlink && !matches!(e.method.as_str(), "store" | "deflate")
    });
    let reclaim = if encrypted_count > 0 {
        ReclaimEligibility::no(
            "Encrypted ZIP archives can be extracted safely, but not reclaimed in place.",
        )
    } else if symlink_count > 0 || unsafe_count > 0 {
        ReclaimEligibility::no("This ZIP contains links or unsafe paths. Use Smart extraction.")
    } else if duplicate {
        ReclaimEligibility::no(
            "This ZIP repeats a file name. Use Smart extraction to resolve it safely.",
        )
    } else if not_plain {
        ReclaimEligibility::no("Only Store and Deflate ZIP compression can be reclaimed.")
    } else if overlaps {
        ReclaimEligibility::no("This ZIP has overlapping entries, which cannot be removed safely.")
    } else {
        ReclaimEligibility::yes()
    };

    info.reclaim = reclaim.clone();
    if reclaim.eligible {
        info.notes.push(
            "Reclaim mode extracts from the last entry backwards and shrinks the ZIP as it goes. The archive is consumed."
                .to_string(),
        );
    }

    Ok(ZipOpen {
        backend: ZipBackend { zip, entries },
        info,
    })
}

impl ZipBackend {
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
        for i in 0..self.entries.len() {
            if !selected.get(i).copied().unwrap_or(false) {
                continue;
            }
            let entry = self.entries[i].clone();
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
                commit_directory(&entry, sink)?;
                continue;
            }
            let opened = if entry.is_encrypted {
                match password {
                    Some(pw) if !pw.is_empty() => self
                        .zip
                        .by_index_decrypt(i, pw.as_bytes())
                        .map_err(map_zip_password)?,
                    _ => return Err(HatchError::password_required()),
                }
            } else {
                self.zip.by_index(i)?
            };
            let mut reader = opened;
            stream_entry(
                &mut reader,
                &entry,
                entry.size,
                entry.crc32,
                false,
                sink,
                control,
            )?;
        }
        Ok(())
    }
}

fn map_zip_password(err: zip::result::ZipError) -> HatchError {
    match err {
        zip::result::ZipError::InvalidPassword => HatchError::wrong_password(),
        other => HatchError::from(other),
    }
}

pub fn method_name(method: zip::CompressionMethod) -> String {
    match method {
        zip::CompressionMethod::Stored => "store".to_string(),
        zip::CompressionMethod::Deflated => "deflate".to_string(),
        zip::CompressionMethod::Deflate64 => "deflate64".to_string(),
        zip::CompressionMethod::Aes => "aes".to_string(),
        other => format!("{other:?}").to_lowercase(),
    }
}

pub fn archive_stem(display_name: &str) -> String {
    let base = display_name.rsplit('/').next().unwrap_or(display_name);
    let lower = base.to_lowercase();
    for ext in [".tar.gz", ".tgz", ".zip", ".rar", ".7z", ".tar", ".gz"] {
        if lower.ends_with(ext) {
            return base[..base.len() - ext.len()].to_string();
        }
    }
    base.to_string()
}
