use crate::error::HatchResult;
use crate::model::Entry;
use std::fs::File;

pub enum EntryOutput {
    File(File),
    Directory,
    Skip,
}

/// Receives destinations and durability callbacks from the extractor.
///
/// `open_entry` is called once per started entry. Returning `Skip` is only
/// allowed in non-destructive Smart mode; Reclaim mode treats it as an error
/// because skipping would discard archive bytes that were not extracted.
pub trait Sink {
    fn open_entry(&mut self, entry: &Entry) -> HatchResult<EntryOutput>;
    fn progress(&mut self, index: usize, written: u64, total: u64) -> HatchResult<bool>;
    /// Called after the entry has been fully written (and synced, in reclaim
    /// mode). Returning false aborts before any archive bytes are removed.
    fn committed(&mut self, index: usize, written: u64) -> HatchResult<bool>;
}

pub struct NullSink {
    pub files: std::collections::HashMap<usize, File>,
    pub fail_commit: bool,
}

impl NullSink {
    pub fn new() -> Self {
        Self {
            files: std::collections::HashMap::new(),
            fail_commit: false,
        }
    }
}

impl Default for NullSink {
    fn default() -> Self {
        Self::new()
    }
}

impl Sink for NullSink {
    fn open_entry(&mut self, _entry: &Entry) -> HatchResult<EntryOutput> {
        Ok(EntryOutput::Skip)
    }

    fn progress(&mut self, _index: usize, _written: u64, _total: u64) -> HatchResult<bool> {
        Ok(true)
    }

    fn committed(&mut self, _index: usize, _written: u64) -> HatchResult<bool> {
        Ok(!self.fail_commit)
    }
}
