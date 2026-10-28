use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::model::{ArchiveInfo, Entry};
use crate::open::{open, Backend, OpenOptions, Opened};
use crate::sink::Sink;
use crate::util::Control;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

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

pub struct TempPaths(pub Vec<PathBuf>);

impl Drop for TempPaths {
    fn drop(&mut self) {
        for path in &self.0 {
            let _ = std::fs::remove_file(path);
        }
    }
}
