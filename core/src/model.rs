use serde::{Deserialize, Serialize};

pub const MODE_SMART: i32 = 0;
pub const MODE_RECLAIM: i32 = 1;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub index: usize,
    pub name: String,
    pub path: String,
    pub size: u64,
    pub compressed_size: u64,
    pub is_directory: bool,
    pub is_encrypted: bool,
    pub is_symlink: bool,
    pub unsafe_path: bool,
    pub modified: i64,
    pub method: String,
    pub crc32: Option<u32>,
    #[serde(skip_serializing)]
    pub native_index: usize,
}

impl Entry {
    pub fn is_extractable(&self) -> bool {
        !self.unsafe_path && !self.is_symlink
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReclaimEligibility {
    pub eligible: bool,
    pub reason: Option<String>,
}

impl ReclaimEligibility {
    pub fn yes() -> Self {
        Self {
            eligible: true,
            reason: None,
        }
    }

    pub fn no(reason: impl Into<String>) -> Self {
        Self {
            eligible: false,
            reason: Some(reason.into()),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArchiveInfo {
    pub format: String,
    pub display_name: String,
    pub archive_size: u64,
    pub entry_count: usize,
    pub file_count: usize,
    pub directory_count: usize,
    pub total_size: u64,
    pub packed_size: u64,
    pub encrypted_count: usize,
    pub solid: bool,
    pub multipart: bool,
    pub password_required: bool,
    pub reclaim: ReclaimEligibility,
    pub notes: Vec<String>,
}

impl ArchiveInfo {
    pub fn base(format: &str, display_name: &str, archive_size: u64) -> Self {
        Self {
            format: format.to_string(),
            display_name: display_name.to_string(),
            archive_size,
            entry_count: 0,
            file_count: 0,
            directory_count: 0,
            total_size: 0,
            packed_size: 0,
            encrypted_count: 0,
            solid: false,
            multipart: false,
            password_required: false,
            reclaim: ReclaimEligibility::no("Only single-file ZIP archives support in-place reclaim."),
            notes: Vec::new(),
        }
    }

    pub fn recompute(&mut self, entries: &[Entry]) {
        self.entry_count = entries.len();
        self.file_count = entries.iter().filter(|e| !e.is_directory).count();
        self.directory_count = entries.iter().filter(|e| e.is_directory).count();
        self.total_size = entries.iter().filter(|e| !e.is_directory).map(|e| e.size).sum();
        self.packed_size = entries.iter().map(|e| e.compressed_size).sum();
        self.encrypted_count = entries.iter().filter(|e| e.is_encrypted).count();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReclaimPlan {
    pub version: u32,
    pub display_name: String,
    pub archive_size: u64,
    pub created_at: i64,
    pub entries: Vec<PlanEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanEntry {
    pub index: usize,
    pub name: String,
    pub path: String,
    pub size: u64,
    pub compressed_size: u64,
    pub is_directory: bool,
    pub modified: i64,
    pub method: String,
    pub crc32: Option<u32>,
    pub header_offset: u64,
    pub data_offset: u64,
    pub data_end: u64,
}

impl PlanEntry {
    pub fn as_entry(&self, index: usize) -> Entry {
        Entry {
            index,
            name: self.name.clone(),
            path: self.path.clone(),
            size: self.size,
            compressed_size: self.compressed_size,
            is_directory: self.is_directory,
            is_encrypted: false,
            is_symlink: false,
            unsafe_path: false,
            modified: self.modified,
            method: self.method.clone(),
            crc32: self.crc32,
            native_index: self.index,
        }
    }
}
