use hatch_core::open::{open, OpenOptions};
use std::fs::File;
use std::path::PathBuf;

fn main() {
    let path = std::env::args().nth(1).expect("archive path");
    let password = std::env::args().nth(2);
    let file = File::open(&path).unwrap();
    let name = PathBuf::from(&path)
        .file_name()
        .unwrap()
        .to_string_lossy()
        .to_string();
    let opened = open(
        file,
        OpenOptions {
            display_name: name,
            password,
            temp_dir: std::env::temp_dir(),
            rar_path: PathBuf::from(&path),
            multipart: false,
            mode: 0,
            plan_path: None,
        },
    )
    .unwrap();
    println!("format={} size={}", opened.info.format, opened.info.archive_size);
    for entry in opened.backend.entries() {
        println!("{} {} {}", entry.index, entry.size, entry.path);
    }
}
