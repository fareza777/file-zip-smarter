use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::extract::stream_entry;
use crate::model::{ArchiveInfo, Entry};
use crate::path::safe_display_name;
use crate::sink::Sink;
use crate::util::Control;
use flate2::read::GzDecoder;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::PathBuf;

pub struct GzipBackend {
    pub file: File,
    pub entry: Entry,
    pub output_name: String,
}

pub struct GzipOpen {
    pub backend: GzipBackend,
    pub info: ArchiveInfo,
}

pub fn open_gzip(mut file: File, display_name: &str, archive_size: u64) -> HatchResult<GzipOpen> {
    let size = gzip_isize(&mut file)?;
    let base = display_name.rsplit('/').next().unwrap_or(display_name);
    let stem = base
        .strip_suffix(".gz")
        .or_else(|| base.strip_suffix(".gzip"))
        .unwrap_or(base);
    let output_name = safe_display_name(stem);
    let entry = Entry {
        index: 0,
        name: output_name.clone(),
        path: output_name.clone(),
        size,
        compressed_size: archive_size.saturating_sub(18),
        is_directory: false,
        is_encrypted: false,
        is_symlink: false,
        unsafe_path: false,
        modified: 0,
        method: "gzip".to_string(),
        crc32: None,
        native_index: 0,
    };
    let mut info = ArchiveInfo::base("GZIP", display_name, archive_size);
    info.recompute(std::slice::from_ref(&entry));
    info.notes.push(
        "GZIP stores one file and no names; the output name is taken from the .gz file."
            .to_string(),
    );
    Ok(GzipOpen {
        backend: GzipBackend {
            file,
            entry,
            output_name,
        },
        info,
    })
}

impl GzipBackend {
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
        let mut decoder = GzDecoder::new(&mut self.file);
        stream_entry(
            &mut decoder,
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

fn gzip_isize(file: &mut File) -> HatchResult<u64> {
    let len = file.metadata()?.len();
    if len < 18 {
        return Err(HatchError::corrupt("This gzip file is too short to be valid."));
    }
    file.seek(SeekFrom::End(-4))?;
    let mut bytes = [0u8; 4];
    file.read_exact(&mut bytes)?;
    file.seek(SeekFrom::Start(0))?;
    Ok(u32::from_le_bytes(bytes) as u64)
}

/// Expands a `.tar.gz` into a private temp TAR. The caller removes the file.
pub fn expand_tar_gz(
    mut file: File,
    temp_path: &PathBuf,
) -> HatchResult<File> {
    file.seek(SeekFrom::Start(0))?;
    let mut decoder = GzDecoder::new(&mut file);
    let mut out = File::create(temp_path)?;
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        let n = decoder.read(&mut buf)?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
    }
    out.flush()?;
    out.sync_all()?;
    drop(out);
    let reopened = File::open(temp_path)?;
    Ok(reopened)
}

pub fn temp_name(temp_dir: &std::path::Path) -> PathBuf {
    let stamp = crate::util::now_millis();
    let pid = std::process::id();
    temp_dir.join(format!("hatch-tar-{pid}-{stamp}.tar"))
}

pub fn unsupported_compressed_container(display_name: &str) -> HatchError {
    let _ = display_name;
    HatchError::new(
        ErrorKind::Unsupported,
        "Hatch reads ZIP, 7z, RAR, TAR, TAR.GZ and GZIP archives.",
    )
}
