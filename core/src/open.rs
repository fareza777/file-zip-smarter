use crate::error::{ErrorKind, HatchError, HatchResult};
use crate::gzip_engine::{expand_tar_gz, open_gzip, temp_name, GzipBackend};
use crate::model::{ArchiveInfo, Entry, MODE_RECLAIM};
use crate::rar_engine::{open_rar, RarBackend};
use crate::reclaim::{open_reclaim, ReclaimBackend};
use crate::sevenz_engine::{open_7z, SevenZBackend};
use crate::sink::Sink;
use crate::tar_engine::{looks_like_tar, open_tar, TarBackend};
use crate::util::Control;
use crate::zip_engine::{open_zip, ZipBackend};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;

pub struct OpenOptions {
    pub display_name: String,
    pub password: Option<String>,
    pub temp_dir: PathBuf,
    pub rar_path: PathBuf,
    pub multipart: bool,
    pub mode: i32,
    pub plan_path: Option<PathBuf>,
}

pub struct Opened {
    pub backend: Backend,
    pub info: ArchiveInfo,
    pub temp_paths: Vec<PathBuf>,
}

pub enum Backend {
    Zip(ZipBackend),
    Tar(TarBackend),
    SevenZ(SevenZBackend),
    Rar(RarBackend),
    Gzip(GzipBackend),
    Reclaim(ReclaimBackend),
}

impl Backend {
    pub fn entries(&self) -> &[Entry] {
        match self {
            Backend::Zip(b) => b.entries(),
            Backend::Tar(b) => b.entries(),
            Backend::SevenZ(b) => b.entries(),
            Backend::Rar(b) => b.entries(),
            Backend::Gzip(b) => b.entries(),
            Backend::Reclaim(b) => b.entries(),
        }
    }

    pub fn extract_selected(
        &mut self,
        selected: &[bool],
        password: Option<&str>,
        sink: &mut dyn Sink,
        control: &Control,
    ) -> HatchResult<()> {
        match self {
            Backend::Zip(b) => b.extract_selected(selected, password, sink, control),
            Backend::Tar(b) => b.extract_selected(selected, password, sink, control),
            Backend::SevenZ(b) => b.extract_selected(selected, password, sink, control),
            Backend::Rar(b) => b.extract_selected(selected, password, sink, control),
            Backend::Gzip(b) => b.extract_selected(selected, password, sink, control),
            Backend::Reclaim(b) => b.extract_selected(selected, password, sink, control),
        }
    }

    pub fn is_reclaim(&self) -> bool {
        matches!(self, Backend::Reclaim(_))
    }
}

pub fn open(mut file: File, options: OpenOptions) -> HatchResult<Opened> {
    let archive_size = file.metadata()?.len();
    let mut magic = [0u8; 8];
    file.seek(SeekFrom::Start(0))?;
    let read = file.read(&mut magic).unwrap_or(0);
    file.seek(SeekFrom::Start(0))?;
    let magic = &magic[..read];
    let display = options.display_name.clone();
    let lower = display.to_lowercase();

    if options.mode == MODE_RECLAIM {
        let plan_path = options.plan_path.ok_or_else(|| {
            HatchError::new(ErrorKind::Internal, "Reclaim mode requires a private journal path.")
        })?;
        if !is_zip_magic(magic) && !lower.ends_with(".zip") {
            return Err(HatchError::reclaim(
                "In-place reclaim is only available for ZIP archives.",
            ));
        }
        let opened = open_reclaim(file, &display, &plan_path)?;
        return Ok(Opened {
            backend: Backend::Reclaim(opened.backend),
            info: opened.info,
            temp_paths: Vec::new(),
        });
    }

    if is_zip_magic(magic) || lower.ends_with(".zip") {
        let opened = open_zip(file, &display, archive_size)?;
        return Ok(Opened {
            backend: Backend::Zip(opened.backend),
            info: opened.info,
            temp_paths: Vec::new(),
        });
    }

    if is_rar_magic(magic) || lower.ends_with(".rar") {
        let opened = open_rar(
            options.rar_path,
            file,
            &display,
            archive_size,
            options.password.as_deref(),
            options.temp_dir,
            options.multipart,
        )?;
        return Ok(Opened {
            backend: Backend::Rar(opened.backend),
            info: opened.info,
            temp_paths: Vec::new(),
        });
    }

    if is_7z_magic(magic) || lower.ends_with(".7z") {
        let opened = open_7z(file, &display, archive_size, options.password.as_deref())?;
        return Ok(Opened {
            backend: Backend::SevenZ(opened.backend),
            info: opened.info,
            temp_paths: Vec::new(),
        });
    }

    let tar_named = lower.ends_with(".tar");
    let tgz_named = lower.ends_with(".tar.gz") || lower.ends_with(".tgz");

    if is_gzip_magic(magic) {
        if tgz_named {
            std::fs::create_dir_all(&options.temp_dir)?;
            let temp = temp_name(&options.temp_dir);
            let expanded = expand_tar_gz(file, &temp)?;
            let mut check = expanded;
            if !looks_like_tar(&mut check) {
                let _ = std::fs::remove_file(&temp);
                return Err(HatchError::corrupt(
                    "This .tar.gz file does not contain a valid TAR archive.",
                ));
            }
            let opened = open_tar(check, &display, archive_size, Some(temp.clone()))?;
            return Ok(Opened {
                backend: Backend::Tar(opened.backend),
                info: opened.info,
                temp_paths: vec![temp],
            });
        }
        let opened = open_gzip(file, &display, archive_size)?;
        return Ok(Opened {
            backend: Backend::Gzip(opened.backend),
            info: opened.info,
            temp_paths: Vec::new(),
        });
    }

    let mut probe = file;
    if tar_named || looks_like_tar(&mut probe) {
        let opened = open_tar(probe, &display, archive_size, None)?;
        return Ok(Opened {
            backend: Backend::Tar(opened.backend),
            info: opened.info,
            temp_paths: Vec::new(),
        });
    }

    if magic.starts_with(b"BZh") {
        return Err(HatchError::unsupported(
            "Bzip2 archives are not supported yet. Expand the .bz2 first, or use a ZIP, 7z, RAR or TAR archive.",
        ));
    }
    if magic.starts_with(&[0xFD, b'7', b'z', b'X', b'Z', 0x00]) {
        return Err(HatchError::unsupported(
            "XZ archives are not supported yet. Hatch reads ZIP, 7z, RAR, TAR, TAR.GZ and GZIP.",
        ));
    }
    if magic.starts_with(&[0x28, 0xB5, 0x2F, 0xFD]) {
        return Err(HatchError::unsupported(
            "Zstandard archives are not supported yet. Hatch reads ZIP, 7z, RAR, TAR, TAR.GZ and GZIP.",
        ));
    }

    Err(HatchError::unsupported(
        "Hatch could not recognise this file as an archive. It reads ZIP, 7z, RAR, TAR, TAR.GZ and GZIP.",
    ))
}

fn is_zip_magic(magic: &[u8]) -> bool {
    magic.starts_with(b"PK\x03\x04")
        || magic.starts_with(b"PK\x05\x06")
        || magic.starts_with(b"PK\x07\x08")
}

fn is_rar_magic(magic: &[u8]) -> bool {
    magic.starts_with(b"Rar!\x1a\x07")
}

fn is_7z_magic(magic: &[u8]) -> bool {
    magic.starts_with(b"7z\xbc\xaf\x27\x1c")
}

fn is_gzip_magic(magic: &[u8]) -> bool {
    magic.starts_with(&[0x1f, 0x8b])
}
