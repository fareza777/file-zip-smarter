use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::model::{ArchiveInfo, Entry};
use crate::open::{open, Backend, OpenOptions, Opened};
use crate::sink::{EntryOutput, Sink};
use crate::util::Control;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

pub struct Inner {
    pub backend: Backend,
    pub info: ArchiveInfo,
    pub temp_paths: TempPaths,
}

pub struct Session {
    pub id: i64,
    pub control: Arc<Control>,
    pub inner: Mutex<Inner>,
}

impl Session {
    pub fn from_opened(id: i64, opened: Opened) -> Self {
        Self {
            id,
            control: Control::new(),
            inner: Mutex::new(Inner {
                backend: opened.backend,
                info: opened.info,
                temp_paths: TempPaths(opened.temp_paths),
            }),
        }
    }

    pub fn open(id: i64, file: std::fs::File, options: OpenOptions) -> HatchResult<Self> {
        Ok(Self::from_opened(id, open(file, options)?))
    }

    pub fn entries_json(&self) -> HatchResult<String> {
        let inner = self
            .inner
            .lock()
            .map_err(|_| HatchError::new(ErrorKind::Internal, "Archive session was locked."))?;
        serde_json::to_string(inner.backend.entries())
            .map_err(|e| HatchError::new(ErrorKind::Internal, e.to_string()))
    }

    pub fn info_json(&self) -> HatchResult<String> {
        let inner = self
            .inner
            .lock()
            .map_err(|_| HatchError::new(ErrorKind::Internal, "Archive session was locked."))?;
        serde_json::to_string(&inner.info)
            .map_err(|e| HatchError::new(ErrorKind::Internal, e.to_string()))
    }

    pub fn entries(&self) -> HatchResult<Vec<Entry>> {
        let inner = self
            .inner
            .lock()
            .map_err(|_| HatchError::new(ErrorKind::Internal, "Archive session was locked."))?;
        Ok(inner.backend.entries().to_vec())
    }

    /// Reads one entry into memory for a quick look. The entry is extracted to
    /// a private temp file first so every backend keeps its normal
    /// verification path; the caller caps the size before this is called.
    pub fn preview_entry(
        &self,
        index: usize,
        password: Option<&str>,
        temp_dir: &Path,
        max_bytes: u64,
    ) -> HatchResult<Vec<u8>> {
        let mut inner = self
            .inner
            .lock()
            .map_err(|_| HatchError::new(ErrorKind::Internal, "Archive session was locked."))?;
        let entries = inner.backend.entries().to_vec();
        let entry = entries.get(index).ok_or_else(|| {
            HatchError::new(ErrorKind::Internal, "That entry is not in this archive.")
        })?;
        if entry.is_directory {
            return Err(HatchError::new(ErrorKind::Internal, "Folders cannot be previewed."));
        }
        if !entry.is_extractable() {
            return Err(HatchError::new(
                ErrorKind::Unsafe,
                "That entry uses a path or link Hatch will not open.",
            ));
        }
        if entry.is_encrypted && password.unwrap_or("").is_empty() {
            return Err(HatchError::password_required());
        }
        if entry.size > max_bytes {
            return Err(HatchError::new(
                ErrorKind::Unsupported,
                "This entry is too large for a quick preview. Extract it to open.",
            ));
        }
        std::fs::create_dir_all(temp_dir)?;
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let path = temp_dir.join(format!("hatch-preview-{index}-{stamp}.bin"));
        let mut selected = vec![false; entries.len()];
        selected[index] = true;
        let mut sink = SingleFileSink {
            target: index,
            path: path.clone(),
            max_bytes,
        };
        self.control.set_cancelled(false);
        let result = inner
            .backend
            .extract_selected(&selected, password, &mut sink, &self.control)
            .and_then(|_| {
                let file = File::open(&path)?;
                let mut data = Vec::new();
                file.take(max_bytes.saturating_add(1)).read_to_end(&mut data)?;
                if data.len() as u64 > max_bytes {
                    return Err(HatchError::new(
                        ErrorKind::Unsupported,
                        "This entry grew past the preview limit. Extract it to open.",
                    ));
                }
                Ok(data)
            });
        let _ = std::fs::remove_file(&path);
        result
    }

    pub fn extract(
        &self,
        selected: &[bool],
        completed: &[bool],
        password: Option<&str>,
        sink: &mut dyn Sink,
    ) -> HatchResult<()> {
        let mut inner = self
            .inner
            .lock()
            .map_err(|_| HatchError::new(ErrorKind::Internal, "Archive session was locked."))?;
        let is_reclaim = inner.backend.is_reclaim();
        if is_reclaim {
            let len = inner.backend.entries().len();
            let selected_len = selected.len();
            if selected_len != len || completed.len() != len {
                return Err(HatchError::new(
                    ErrorKind::Internal,
                    "Reclaim needs a complete entry map. Nothing was removed.",
                ));
            }
            for i in 0..len {
                if !selected[i] && !completed[i] {
                    return Err(HatchError::new(
                        ErrorKind::Internal,
                        "Reclaim cannot leave an entry unaccounted for. Nothing was removed.",
                    ));
                }
            }
        }
        self.control.set_cancelled(false);
        inner
            .backend
            .extract_selected(selected, password, sink, &self.control)
    }
}

struct SingleFileSink {
    target: usize,
    path: PathBuf,
    max_bytes: u64,
}

impl Sink for SingleFileSink {
    fn open_entry(&mut self, entry: &Entry) -> HatchResult<EntryOutput> {
        if entry.index != self.target || entry.is_directory {
            return Ok(EntryOutput::Skip);
        }
        if entry.size > self.max_bytes {
            return Err(HatchError::new(
                ErrorKind::Unsupported,
                "This entry is too large for a quick preview. Extract it to open.",
            ));
        }
        Ok(EntryOutput::File(File::create(&self.path)?))
    }

    fn progress(&mut self, index: usize, written: u64, _total: u64) -> HatchResult<bool> {
        if index == self.target && written > self.max_bytes {
            return Ok(false);
        }
        Ok(true)
    }

    fn committed(&mut self, _index: usize, _written: u64) -> HatchResult<bool> {
        Ok(true)
    }
}

pub struct TempPaths(pub Vec<PathBuf>);

impl Drop for TempPaths {
    fn drop(&mut self) {
        for path in &self.0 {
            let _ = std::fs::remove_file(path);
        }
    }
}
