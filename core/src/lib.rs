pub mod compressed_engine;
pub mod error;
pub mod extract;
pub mod gzip_engine;
pub mod model;
pub mod open;
pub mod path;
pub mod rar_engine;
pub mod reclaim;
pub mod sevenz_engine;
pub mod sink;
pub mod tar_engine;
pub mod util;
pub mod zip_engine;
pub mod session;

#[cfg(target_os = "android")]
mod jni_bridge;

#[cfg(test)]
mod tests {
    use crate::model::Entry;
    use crate::open::{open, OpenOptions};
    use crate::sink::{EntryOutput, Sink};
    use crate::util::Control;
    use std::fs::{File, OpenOptions as FsOptions};
    use std::io::Write;
    use std::path::{Path, PathBuf};

    struct DirSink {
        dir: PathBuf,
    }

    impl Sink for DirSink {
        fn open_entry(&mut self, entry: &Entry) -> crate::error::HatchResult<EntryOutput> {
            let path = self.dir.join(&entry.path);
            if entry.is_directory {
                std::fs::create_dir_all(&path)?;
                return Ok(EntryOutput::Directory);
            }
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            Ok(EntryOutput::File(File::create(path)?))
        }

        fn progress(&mut self, _index: usize, _written: u64, _total: u64) -> crate::error::HatchResult<bool> {
            Ok(true)
        }

        fn committed(&mut self, _index: usize, _written: u64) -> crate::error::HatchResult<bool> {
            Ok(true)
        }
    }

    fn write_zip(path: &Path) -> File {
        let file = File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        zip.start_file("docs/readme.txt", opts).unwrap();
        zip.write_all(b"hatch keeps this byte-for-byte").unwrap();
        zip.start_file("image.bin", opts).unwrap();
        zip.write_all(&[7u8; 100_000]).unwrap();
        zip.finish().unwrap();
        FsOptions::new().read(true).open(path).unwrap()
    }

    fn options(name: &str, temp: &Path) -> OpenOptions {
        OpenOptions {
            display_name: name.to_string(),
            password: None,
            temp_dir: temp.join("temp"),
            rar_path: temp.join(name),
            multipart: false,
            mode: 0,
            plan_path: None,
        }
    }

    #[test]
    fn lists_and_extracts_zip() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("sample.zip");
        let file = write_zip(&path);
        let opened = open(file, options("sample.zip", tmp.path())).unwrap();
        assert_eq!(opened.backend.entries().len(), 2);
        assert!(opened.info.reclaim.eligible);
        let selected = vec![true, true];
        let completed = vec![false, false];
        let mut sink = DirSink {
            dir: tmp.path().join("out"),
        };
        let control = Control::default();
        let mut backend = opened.backend;
        backend
            .extract_selected(&selected, None, &mut sink, &control)
            .unwrap();
        assert_eq!(
            std::fs::read_to_string(tmp.path().join("out/docs/readme.txt")).unwrap(),
            "hatch keeps this byte-for-byte"
        );
        assert_eq!(
            std::fs::metadata(tmp.path().join("out/image.bin")).unwrap().len(),
            100_000
        );
        let _ = completed;
    }

    #[test]
    fn detects_unsafe_paths() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("evil.zip");
        {
            let file = File::create(&path).unwrap();
            let mut zip = zip::ZipWriter::new(file);
            let opts = zip::write::SimpleFileOptions::default();
            zip.start_file("../../escape.txt", opts).unwrap();
            zip.write_all(b"no").unwrap();
            zip.finish().unwrap();
        }
        let file = FsOptions::new().read(true).open(&path).unwrap();
        let opened = open(file, options("evil.zip", tmp.path())).unwrap();
        assert!(opened.backend.entries()[0].unsafe_path);
        assert!(!opened.info.reclaim.eligible);
    }
}
