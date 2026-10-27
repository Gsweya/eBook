//! Library backups as a single .zip — `library.json` plus `books/…` — the same layout as the Android app's,
//! so a phone backup restores on the desktop and the other way round.

use std::fs;
use std::io::{self, Read, Write};
use std::path::{Component, Path};

use crate::model::{LibraryData, now_ms};
use crate::store::Paths;

/// Writes every book and the library to `dest`. Returns the backup time to record.
pub fn export(paths: &Paths, data: &LibraryData, dest: &Path) -> io::Result<i64> {
    let at = now_ms();
    let mut snapshot = data.clone();
    snapshot.last_backup_at = at;
    let tmp = dest.with_extension("partial");
    {
        let f = fs::File::create(&tmp)?;
        let mut zip = zip::ZipWriter::new(io::BufWriter::new(f));
        let deflated = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        // Book files are already compressed (EPUB, PDF, JPEG); storing them is as small and much faster.
        let stored = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored)
            .large_file(true);
        zip.start_file("library.json", deflated)
            .map_err(io::Error::other)?;
        zip.write_all(&serde_json::to_vec(&snapshot)?)?;
        let root = paths.books_dir();
        let mut stack = vec![root.clone()];
        while let Some(dir) = stack.pop() {
            let Ok(entries) = fs::read_dir(&dir) else {
                continue;
            };
            for e in entries.flatten() {
                let p = e.path();
                if p.is_dir() {
                    stack.push(p);
                    continue;
                }
                let rel = p
                    .strip_prefix(&root)
                    .unwrap_or(&p)
                    .to_string_lossy()
                    .replace('\\', "/");
                let opts = if rel.ends_with(".json") {
                    deflated
                } else {
                    stored
                };
                zip.start_file(format!("books/{rel}"), opts)
                    .map_err(io::Error::other)?;
                io::copy(&mut fs::File::open(&p)?, &mut zip)?;
            }
        }
        zip.finish().map_err(io::Error::other)?.flush()?;
    }
    fs::rename(tmp, dest)?;
    Ok(at)
}

/// Unpacks a backup's books into the library folder and returns its library data, for merging.
pub fn read(paths: &Paths, src: &Path) -> Result<LibraryData, String> {
    let f = fs::File::open(src).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(f).map_err(|_| "Not a GBook backup".to_string())?;
    let mut incoming: Option<LibraryData> = None;
    let root = paths.books_dir();
    for i in 0..zip.len() {
        let mut e = zip.by_index(i).map_err(|e| e.to_string())?;
        let Ok(name) = e.name().map(|n| n.to_string()) else {
            continue;
        };
        if name == "library.json" {
            let mut s = Vec::new();
            e.read_to_end(&mut s).map_err(|e| e.to_string())?;
            incoming = Some(
                serde_json::from_slice(&s).map_err(|e| format!("Unreadable library.json: {e}"))?,
            );
        } else if let Some(rel) = name.strip_prefix("books/") {
            if e.is_dir() {
                continue;
            }
            // Zip-slip guard: only plain relative paths inside books/.
            let rel = Path::new(rel);
            if rel.components().any(|c| !matches!(c, Component::Normal(_))) {
                continue;
            }
            let out = root.join(rel);
            if let Some(dir) = out.parent() {
                fs::create_dir_all(dir).map_err(|e| e.to_string())?;
            }
            let mut w = fs::File::create(&out).map_err(|e| e.to_string())?;
            io::copy(&mut e, &mut w).map_err(|e| e.to_string())?;
        }
    }
    incoming.ok_or_else(|| "Not a GBook backup (no library.json)".to_string())
}

/// Keeps everything already here and adds what's new from the backup, as the phone does. Returns how many books
/// came in.
pub fn merge(current: &mut LibraryData, incoming: LibraryData) -> usize {
    let n = incoming.books.len();
    for b in incoming.books {
        if current.book(&b.id).is_none() {
            current.books.push(b);
        }
    }
    for h in incoming.highlights {
        if !current.highlights.iter().any(|x| x.id == h.id) {
            current.highlights.push(h);
        }
    }
    for p in incoming.posts {
        if !current.posts.iter().any(|x| x.id == p.id) {
            current.posts.push(p);
        }
    }
    for c in incoming.collections {
        if !current.collections.iter().any(|x| x.id == c.id) {
            current.collections.push(c);
        }
    }
    for p in incoming.plans {
        if !current.plans.iter().any(|x| x.book_id == p.book_id) {
            current.plans.push(p);
        }
    }
    // Reading time per day: keep the larger figure, since both devices may have logged the same day.
    for (day, ms) in incoming.reading_log {
        let e = current.reading_log.entry(day).or_default();
        *e = (*e).max(ms);
    }
    for (day, books) in incoming.book_days {
        let cur = current.book_days.entry(day).or_default();
        for (id, ms) in books {
            let e = cur.entry(id).or_default();
            *e = (*e).max(ms);
        }
    }
    for (day, n) in incoming.pages_log {
        let e = current.pages_log.entry(day).or_default();
        *e = (*e).max(n);
    }
    n
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Book;

    #[test]
    fn export_then_read() {
        let dir = std::env::temp_dir().join(format!("gbook-backup-test-{}", std::process::id()));
        let paths = Paths {
            root: dir.join("a"),
        };
        fs::create_dir_all(paths.book_dir("b1")).unwrap();
        fs::write(paths.book_dir("b1").join("book.epub"), b"epub").unwrap();
        let mut d = LibraryData::default();
        d.books.push(Book {
            id: "b1".into(),
            title: "One".into(),
            ..Default::default()
        });
        let zip = dir.join("backup.zip");
        export(&paths, &d, &zip).unwrap();
        let other = Paths {
            root: dir.join("b"),
        };
        let incoming = read(&other, &zip).unwrap();
        assert_eq!(incoming.books[0].title, "One");
        assert_eq!(
            fs::read(other.book_dir("b1").join("book.epub")).unwrap(),
            b"epub"
        );
        let mut cur = LibraryData::default();
        assert_eq!(merge(&mut cur, incoming), 1);
        assert_eq!(cur.books.len(), 1);
        fs::remove_dir_all(dir).ok();
    }
}
