//! On-disk layout, the same as the Android app's files dir so a backup restores on either:
//!
//! ```text
//! library.json
//! profile.jpg
//! books/<id>/book.epub | book.pdf
//! books/<id>/cover
//! books/<id>/content.json
//! books/<id>/images/…
//! ```

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use crate::model::{Book, BookContent, LibraryData};

#[derive(Clone, Debug)]
pub struct Paths {
    pub root: PathBuf,
}

impl Paths {
    /// `$GBOOK_DATA_DIR`, else `~/.local/share/gbook` (`%APPDATA%\GBook` on Windows).
    pub fn default_location() -> Paths {
        let root = std::env::var_os("GBOOK_DATA_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                let base = dirs::data_dir().unwrap_or_else(|| PathBuf::from("."));
                base.join(if cfg!(windows) { "GBook" } else { "gbook" })
            });
        Paths { root }
    }

    pub fn library(&self) -> PathBuf {
        self.root.join("library.json")
    }
    pub fn avatar(&self) -> PathBuf {
        self.root.join("profile.jpg")
    }
    pub fn books_dir(&self) -> PathBuf {
        self.root.join("books")
    }
    pub fn book_dir(&self, id: &str) -> PathBuf {
        self.books_dir().join(id)
    }
    pub fn book_file(&self, b: &Book) -> PathBuf {
        self.book_dir(&b.id).join(&b.file_name)
    }
    pub fn cover(&self, id: &str) -> PathBuf {
        self.book_dir(id).join("cover")
    }
    pub fn images_dir(&self, id: &str) -> PathBuf {
        self.book_dir(id).join("images")
    }
    pub fn content(&self, id: &str) -> PathBuf {
        self.book_dir(id).join("content.json")
    }
    pub fn cache_dir(&self) -> PathBuf {
        self.root.join("cache")
    }
}

/// Reads the library, or an empty one if it's missing. A file that exists but can't be parsed is set aside as
/// `library.json.broken` rather than silently overwritten later.
pub fn load(paths: &Paths) -> LibraryData {
    let f = paths.library();
    match fs::read(&f) {
        Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|e| {
            eprintln!("gbook: can't read {}: {e}", f.display());
            let _ = fs::rename(&f, f.with_extension("json.broken"));
            LibraryData::default()
        }),
        Err(_) => LibraryData::default(),
    }
}

/// Writes `data` to a temporary file and renames it over `library.json`, so a crash never leaves half a file.
pub fn save(paths: &Paths, data: &LibraryData) -> io::Result<()> {
    write_atomic(&paths.library(), &serde_json::to_vec(data)?)
}

pub fn write_atomic(path: &Path, bytes: &[u8]) -> io::Result<()> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let tmp = path.with_extension("tmp");
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_data()?;
    }
    fs::rename(tmp, path)
}

pub fn load_content(paths: &Paths, id: &str) -> Option<BookContent> {
    let bytes = fs::read(paths.content(id)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub fn save_content(paths: &Paths, id: &str, c: &BookContent) -> io::Result<()> {
    write_atomic(&paths.content(id), &serde_json::to_vec(c)?)
}

pub fn delete_book_files(paths: &Paths, id: &str) {
    if !id.is_empty() {
        let _ = fs::remove_dir_all(paths.book_dir(id));
    }
}
