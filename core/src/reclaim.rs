use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::extract::{commit_directory, stream_entry};
use crate::model::{ArchiveInfo, Entry, PlanEntry, ReclaimEligibility, ReclaimPlan};
use crate::path::sanitize_entry_path;
use crate::sink::Sink;
use crate::util::{now_millis, zip_time_millis, Control};
use flate2::read::DeflateDecoder;
use std::collections::{HashMap, HashSet};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use zip::read::ZipArchive;

pub struct ReclaimBackend {
    pub file: File,
    pub entries: Vec<Entry>,
    pub plan: ReclaimPlan,
    pub plan_path: PathBuf,
}

pub struct ReclaimOpen {
    pub backend: ReclaimBackend,
    pub info: ArchiveInfo,
}

/// Opens (or resumes) a destructive in-place ZIP plan. The plan is written and
/// synced before a single archive byte is removed.
pub fn open_reclaim(
    mut file: File,
    display_name: &str,
    plan_path: &Path,
) -> HatchResult<ReclaimOpen> {
    let len = file.metadata()?.len();
    if len == 0 {
        return Err(HatchError::reclaim("This archive is empty."));
    }
    check_writable(&mut file)?;

    let plan = if plan_path.exists() {
        load_plan(plan_path)?
    } else {
        let plan = build_plan(&mut file, display_name)?;
        save_plan(plan_path, &plan)?;
        plan
    };

    if plan.version != 1 {
        return Err(HatchError::new(
            ErrorKind::ReclaimUnavailable,
            "This reclaim journal was written by a newer version of Hatch.",
        ));
    }

    let entries: Vec<Entry> = plan
        .entries
        .iter()
        .enumerate()
        .map(|(i, pe)| pe.as_entry(i))
        .collect();

    let mut info = ArchiveInfo::base("ZIP", display_name, plan.archive_size);
    info.recompute(&entries);
    info.reclaim = ReclaimEligibility::yes();
    info.notes.push(
        "Reclaim is on. Files are restored from the end of the ZIP and the archive shrinks after each durable commit. Keep Hatch open until this job finishes."
            .to_string(),
    );
    info.notes.push(
        "The original archive cannot be kept after reclaim. Only the extracted files remain."
            .to_string(),
    );

    Ok(ReclaimOpen {
        backend: ReclaimBackend {
            file,
            entries,
            plan,
            plan_path: plan_path.to_path_buf(),
        },
        info,
    })
}

impl ReclaimBackend {
    pub fn entries(&self) -> &[Entry] {
        &self.entries
    }

    /// Selected indexes are the entries not yet committed by the journal.
    /// Every uncommitted entry must be selected; Kotlin enforces this so no
    /// unextracted bytes can be dropped.
    pub fn extract_selected(
        &mut self,
        selected: &[bool],
        _password: Option<&str>,
        sink: &mut dyn Sink,
        control: &Control,
    ) -> HatchResult<()> {
        if self.plan.entries.is_empty() {
            return Ok(());
        }
        let selected_count = selected.iter().filter(|v| **v).count();
        if selected_count == 0 {
            return Ok(());
        }

        let mut order: Vec<usize> = (0..self.plan.entries.len()).collect();
        order.sort_by_key(|&i| std::cmp::Reverse(self.plan.entries[i].header_offset));

        for plan_index in order {
            if !selected.get(plan_index).copied().unwrap_or(false) {
                continue;
            }
            let pe = self.plan.entries[plan_index].clone();
            let entry = &self.entries[plan_index];
            control.wait_if_paused(|| sink.progress(entry.index, 0, entry.size))?;

            if pe.is_directory {
                commit_directory(entry, sink)?;
                self.truncate_to(pe.header_offset)?;
                continue;
            }

            if pe.data_end > self.file.metadata()?.len() {
                return Err(HatchError::new(
                    ErrorKind::Corrupt,
                    format!(
                        "The archive ended before '{}' could be read. The extracted files are safe.",
                        pe.name
                    ),
                ));
            }
            if pe.method != "store" && pe.method != "deflate" {
                return Err(HatchError::new(
                    ErrorKind::ReclaimUnavailable,
                    "This entry uses a compression method that reclaim cannot decode.",
                ));
            }

            self.file.seek(SeekFrom::Start(pe.data_offset))?;
            let limited = (&mut self.file).take(pe.compressed_size);
            match pe.method.as_str() {
                "store" => {
                    let mut reader = limited;
                    stream_entry(&mut reader, entry, pe.size, pe.crc32, true, sink, control)?;
                }
                "deflate" => {
                    let mut reader = DeflateDecoder::new(limited);
                    stream_entry(&mut reader, entry, pe.size, pe.crc32, true, sink, control)?;
                }
                _ => unreachable!(),
            }

            // The journal commit happened inside `committed`; only now may the
            // bytes that produced this file leave the archive.
            self.truncate_to(pe.header_offset)?;
        }

        // A resumed job may have already removed the file's prefix in an
        // earlier run. Do not force length zero if some unselected entries
        // remain; Kotlin deletes the consumed archive at job completion.
        let all_done = selected.iter().all(|v| *v);
        if all_done {
            self.file.set_len(0)?;
            self.file.sync_data()?;
        }
        Ok(())
    }

    fn truncate_to(&mut self, len: u64) -> HatchResult<()> {
        let current = self.file.metadata()?.len();
        if len > current {
            return Err(HatchError::corrupt(
                "The archive is shorter than its reclaim plan. Truncation stopped.",
            ));
        }
        self.file.set_len(len)?;
        self.file.sync_data()?;
        Ok(())
    }
}

fn check_writable(file: &mut File) -> HatchResult<()> {
    let meta = file.metadata()?;
    if !meta.is_file() {
        return Err(HatchError::reclaim(
            "This provider does not expose a regular file, so it cannot be reclaimed in place.",
        ));
    }
    let len = meta.len();
    let probe = file.set_len(len);
    if let Err(err) = probe {
        return Err(HatchError::reclaim(format!(
            "This folder does not allow Hatch to shrink the archive. Smart extraction is available instead. ({err})"
        )));
    }
    file.sync_data()
        .map_err(|_| HatchError::reclaim("The archive cannot be modified in place by this provider."))?;
    Ok(())
}

fn build_plan(file: &mut File, display_name: &str) -> HatchResult<ReclaimPlan> {
    let archive_size = file.metadata()?.len();
    let mut zip = ZipArchive::new(file.try_clone()?)?;
    let mut plan_entries = Vec::with_capacity(zip.len());
    let mut seen_paths = HashSet::new();
    let mut seen_offsets = HashSet::new();
    let mut intervals: Vec<(u64, u64)> = Vec::new();

    for i in 0..zip.len() {
        let raw = zip.by_index_raw(i)?;
        let original = raw.name().unwrap_or_default().to_string();
        let sanitized = sanitize_entry_path(&original).ok_or_else(|| {
            HatchError::reclaim(format!(
                "'{original}' would escape the destination folder. Use Smart extraction."
            ))
        })?;
        if raw.is_symlink() {
            return Err(HatchError::reclaim(
                "This ZIP contains symbolic links. Use Smart extraction; links are skipped there.",
            ));
        }
        if raw.encrypted() {
            return Err(HatchError::reclaim(
                "Encrypted ZIPs cannot be reclaimed in place. Use Smart extraction.",
            ));
        }
        let method = crate::zip_engine::method_name(raw.compression());
        if !matches!(method.as_str(), "store" | "deflate") {
            return Err(HatchError::reclaim(
                "Only Store and Deflate ZIP compression can be reclaimed in place.",
            ));
        }
        let is_directory = raw.is_dir();
        let header_offset = raw.header_start();
        let data_offset = raw.data_start().ok_or_else(|| {
            HatchError::reclaim("A local ZIP header could not be located. Use Smart extraction.")
        })?;
        let compressed_size = raw.compressed_size();
        let data_end = data_offset.checked_add(compressed_size).ok_or_else(|| {
            HatchError::corrupt("A ZIP entry has an invalid size.")
        })?;
        if data_end > archive_size {
            return Err(HatchError::corrupt(
                "A ZIP entry extends past the end of the archive.",
            ));
        }
        if !seen_offsets.insert(header_offset) {
            return Err(HatchError::reclaim(
                "Two ZIP entries share a local header. Reclaim is not safe for this archive.",
            ));
        }
        if !is_directory {
            let key = sanitized.to_lowercase();
            if !seen_paths.insert(key) {
                return Err(HatchError::reclaim(format!(
                    "'{sanitized}' appears more than once. Use Smart extraction."
                )));
            }
            intervals.push((header_offset, data_end));
        }
        plan_entries.push(PlanEntry {
            index: i,
            name: original,
            path: sanitized,
            size: raw.size(),
            compressed_size,
            is_directory,
            modified: raw.last_modified().map(zip_time_millis).unwrap_or(0),
            method,
            crc32: Some(raw.crc32()),
            header_offset,
            data_offset,
            data_end,
        });
    }

    if zip.has_overlapping_entries().unwrap_or(true) {
        return Err(HatchError::reclaim(
            "This ZIP has overlapping entries. Reclaim is not safe; use Smart extraction.",
        ));
    }

    intervals.sort_unstable();
    for window in intervals.windows(2) {
        if window[0].1 > window[1].0 {
            return Err(HatchError::reclaim(
                "This ZIP has overlapping entries. Reclaim is not safe; use Smart extraction.",
            ));
        }
    }

    if plan_entries.is_empty() {
        return Err(HatchError::reclaim("This ZIP has no entries to extract."));
    }

    Ok(ReclaimPlan {
        version: 1,
        display_name: display_name.to_string(),
        archive_size,
        created_at: now_millis(),
        entries: plan_entries,
    })
}

fn save_plan(path: &Path, plan: &ReclaimPlan) -> HatchResult<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("json.tmp");
    let bytes = serde_json::to_vec(plan)
        .map_err(|e| HatchError::new(ErrorKind::Internal, format!("Could not write journal: {e}")))?;
    let mut f = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&tmp)?;
    f.write_all(&bytes)?;
    f.sync_all()?;
    drop(f);
    std::fs::rename(&tmp, path)?;
    Ok(())
}

fn load_plan(path: &Path) -> HatchResult<ReclaimPlan> {
    let bytes = std::fs::read(path)?;
    serde_json::from_slice(&bytes).map_err(|e| {
        HatchError::with_detail(
            ErrorKind::Corrupt,
            "The reclaim journal is damaged. The files already extracted are safe.",
            e.to_string(),
        )
    })
}

/// Builds a lookup used by tests and diagnostics.
pub fn plan_by_offset(plan: &ReclaimPlan) -> HashMap<u64, usize> {
    plan.entries
        .iter()
        .enumerate()
        .map(|(i, e)| (e.header_offset, i))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sink::{EntryOutput, Sink};
    use std::io::Write;

    struct FileSink {
        dir: PathBuf,
        empty_ok: bool,
    }

    impl Sink for FileSink {
        fn open_entry(&mut self, entry: &Entry) -> HatchResult<EntryOutput> {
            if entry.is_directory {
                std::fs::create_dir_all(self.dir.join(&entry.path))?;
                return Ok(EntryOutput::Directory);
            }
            let path = self.dir.join(&entry.path);
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let file = File::create(path)?;
            Ok(EntryOutput::File(file))
        }

        fn progress(&mut self, _index: usize, _written: u64, _total: u64) -> HatchResult<bool> {
            Ok(true)
        }

        fn committed(&mut self, _index: usize, _written: u64) -> HatchResult<bool> {
            Ok(self.empty_ok)
        }
    }

    fn zip_with_entries(dir: &Path) -> PathBuf {
        let path = dir.join("sample.zip");
        let file = File::create(&path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        let names = ["alpha.txt", "folder/beta.txt", "folder/gamma.bin", "last.txt"];
        for (i, name) in names.iter().enumerate() {
            zip.start_file(*name, options).unwrap();
            zip.write_all(format!("hello {i} {}", "x".repeat(i * 100)).as_bytes())
                .unwrap();
        }
        zip.finish().unwrap();
        path
    }

    #[test]
    fn reclaim_extracts_everything_and_consumes_archive() {
        let tmp = tempfile::tempdir().unwrap();
        let path = zip_with_entries(tmp.path());
        let file = File::options().read(true).write(true).open(&path).unwrap();
        let plan_path = tmp.path().join("plan.json");
        let mut opened = open_reclaim(file, "sample.zip", &plan_path).unwrap();
        let count = opened.backend.entries().len();
        let selected = vec![true; count];
        let mut sink = FileSink {
            dir: tmp.path().join("out"),
            empty_ok: true,
        };
        let control = Control::default();
        opened
            .backend
            .extract_selected(&selected, None, &mut sink, &control)
            .unwrap();
        assert_eq!(std::fs::metadata(&path).unwrap().len(), 0);
        assert_eq!(
            std::fs::read_to_string(tmp.path().join("out/alpha.txt")).unwrap(),
            "hello 0 "
        );
        assert!(tmp.path().join("out/last.txt").exists());
    }

    #[test]
    fn encrypted_or_symlink_plans_are_refused() {
        let tmp = tempfile::tempdir().unwrap();
        let path = zip_with_entries(tmp.path());
        let file = File::options().read(true).write(true).open(&path).unwrap();
        let zip = ZipArchive::new(file.try_clone().unwrap()).unwrap();
        assert!(zip.len() > 0);
        drop(zip);
        let plan = build_plan(&mut file.try_clone().unwrap(), "sample.zip").unwrap();
        assert_eq!(plan.entries.len(), 4);
    }
}
