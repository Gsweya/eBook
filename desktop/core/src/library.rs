//! Changes to the library, ported from the Android `AppViewModel` so both apps record reading time,
//! highlights, reactions and collections the same way.

use std::collections::BTreeMap;
use std::fs;
use std::io::Read;
use std::path::Path;

use crate::dates;
use crate::epub;
use crate::model::*;
use crate::store::{self, Paths};

/// Image and PDF work the core can't do on its own; the app provides it with the platform's libraries.
pub trait Media {
    /// Renders page 1 of the PDF to `out` as PNG and returns the page count.
    fn pdf_cover(&self, pdf: &Path, out: &Path) -> Result<i32, String>;
    /// (title, author) from the PDF's metadata, either possibly empty.
    fn pdf_metadata(&self, pdf: &Path) -> (String, String);
    /// The cover's average colour, darkened so white text stays legible on it, as 0xAARRGGBB.
    fn cover_color(&self, image: &Path) -> Option<i64>;
}

const SMALL_WORDS: &[&str] = &[
    "a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs",
];

/// A readable title from a file name: "the_art-of_war (z-lib.org).epub" becomes "The Art of War".
pub fn title_from_file_name(name: &str) -> String {
    let stem = name.rsplit_once('.').map(|(s, _)| s).unwrap_or(name);
    let mut s = stem.to_string();
    if let Some(i) = s.to_lowercase().find("(z-lib")
        && let Some(j) = s[i..].find(')')
    {
        s.replace_range(i..i + j + 1, "");
    }
    let words: Vec<String> = s
        .replace(['_', '-'], " ")
        .split_whitespace()
        .enumerate()
        .map(|(i, w)| {
            if i == 0 || !SMALL_WORDS.contains(&w.to_lowercase().as_str()) {
                let mut c = w.chars();
                c.next()
                    .map(|f| f.to_uppercase().chain(c).collect())
                    .unwrap_or_default()
            } else {
                w.to_string()
            }
        })
        .collect();
    words.join(" ")
}

/// Short content hash used as the book id, so importing the same file twice finds the existing book.
pub fn book_id(file: &Path) -> std::io::Result<String> {
    let mut f = fs::File::open(file)?;
    let mut h = sha1_smol::Sha1::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.digest().to_string()[..12].to_string())
}

pub fn is_pdf(file: &Path) -> bool {
    let mut magic = [0u8; 4];
    fs::File::open(file)
        .and_then(|mut f| f.read_exact(&mut magic))
        .is_ok()
        && &magic[..2] == b"%P"
}

pub enum Imported {
    /// The library already had this file.
    Existing(Book),
    /// A new book, already copied into the library folder but not yet added to `LibraryData`.
    New(Book),
}

/// Copies `src` into the library and reads it. Runs off the UI thread; the caller adds the book to the data.
/// PDFs come back with `text_ready = false`: their reflowable text is extracted separately.
pub fn import_file(
    paths: &Paths,
    data_has: &dyn Fn(&str) -> Option<Book>,
    src: &Path,
    media: &dyn Media,
) -> Result<Imported, String> {
    let id = book_id(src).map_err(|e| format!("Couldn't read the file: {e}"))?;
    if let Some(b) = data_has(&id) {
        return Ok(Imported::Existing(b));
    }
    let name = src
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "Book".into());
    let fallback = title_from_file_name(&name);
    let dir = paths.book_dir(&id);
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let cover = paths.cover(&id);
    let result = if is_pdf(src) {
        let f = dir.join("book.pdf");
        fs::copy(src, &f).map_err(|e| e.to_string())?;
        let pages = media.pdf_cover(&f, &cover)?;
        let (t, a) = media.pdf_metadata(&f);
        Book {
            id: id.clone(),
            title: if t.trim().is_empty() {
                fallback
            } else {
                t.trim().to_string()
            },
            author: if a.trim().is_empty() {
                "Unknown author".into()
            } else {
                a.trim().to_string()
            },
            format: BookFormat::PDF,
            file_name: "book.pdf".into(),
            has_cover: true,
            cover_color: media.cover_color(&cover).unwrap_or(DEFAULT_COVER_COLOR),
            page_count: pages,
            text_ready: false,
            ..Default::default()
        }
    } else {
        let f = dir.join("book.epub");
        fs::copy(src, &f).map_err(|e| e.to_string())?;
        let r = epub::parse(&f, &paths.images_dir(&id))?;
        let has_cover = match &r.cover {
            Some(bytes) => fs::write(&cover, bytes).is_ok(),
            None => false,
        };
        store::save_content(paths, &id, &r.content).map_err(|e| e.to_string())?;
        Book {
            id: id.clone(),
            title: if r.title.trim().is_empty() {
                fallback
            } else {
                r.title.trim().to_string()
            },
            author: if r.author.trim().is_empty() {
                "Unknown author".into()
            } else {
                r.author.trim().to_string()
            },
            format: BookFormat::EPUB,
            file_name: "book.epub".into(),
            has_cover,
            cover_color: if has_cover {
                media.cover_color(&cover).unwrap_or(DEFAULT_COVER_COLOR)
            } else {
                DEFAULT_COVER_COLOR
            },
            chapter_count: r.content.chapters.len() as i32,
            ..Default::default()
        }
    };
    Ok(Imported::New(result))
}

/// Short random-looking id for highlights, collections and the like.
pub fn new_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut h = std::collections::hash_map::RandomState::new().build_hasher();
    h.write_i64(now_ms());
    format!("{:012x}", h.finish() & 0xffff_ffff_ffff)
}

impl LibraryData {
    /// Books, most recently opened or added first.
    pub fn books_by_recent(&self) -> Vec<&Book> {
        let mut v: Vec<&Book> = self.books.iter().collect();
        v.sort_by_key(|b| std::cmp::Reverse(b.last_opened_at.max(b.added_at)));
        v
    }

    pub fn mark_opened(&mut self, id: &str) {
        if let Some(b) = self.book_mut(id) {
            b.last_opened_at = now_ms();
        }
    }

    /// Adds active reading time to a book and to `day`'s total. Returns today's minutes when this crossed the
    /// daily goal for the first time today, so the reader can celebrate.
    pub fn add_reading_time(&mut self, book_id: &str, ms: i64, day: &str) -> Option<i64> {
        if ms <= 0 {
            return None;
        }
        let before = self.reading_log.get(day).copied().unwrap_or(0);
        let goal = self.settings.daily_goal_min as i64 * 60_000;
        let reached =
            goal > 0 && before < goal && before + ms >= goal && self.goal_reached_day != day;
        if reached {
            self.goal_reached_day = day.to_string();
        }
        if let Some(b) = self.book_mut(book_id) {
            b.reading_ms += ms;
        }
        *self.reading_log.entry(day.to_string()).or_default() += ms;
        *self
            .book_days
            .entry(day.to_string())
            .or_default()
            .entry(book_id.to_string())
            .or_default() += ms;
        reached.then(|| (before + ms) / 60_000)
    }

    pub fn add_pages_read(&mut self, book_id: &str, n: i32, day: &str) {
        if let Some(b) = self.book_mut(book_id) {
            b.pages_read += n;
        }
        *self.pages_log.entry(day.to_string()).or_default() += n;
    }

    pub fn save_position(&mut self, id: &str, pos: ReadingPosition, progress: f32) {
        if let Some(b) = self.book_mut(id) {
            b.position = pos;
            b.progress = progress.clamp(0.0, 1.0);
        }
    }

    pub fn react_book(&mut self, id: &str, emoji: &str) {
        if let Some(b) = self.book_mut(id) {
            toggle_reaction(&mut b.reactions, &mut b.my_reaction, emoji);
        }
    }

    /// Rating it the same again clears the rating.
    pub fn rate_book(&mut self, id: &str, stars: i32) {
        if let Some(b) = self.book_mut(id) {
            b.rating = if b.rating == stars { 0 } else { stars };
        }
    }

    /// Removes the book and everything tied to it; the caller deletes its files.
    pub fn remove_book(&mut self, id: &str) {
        self.books.retain(|b| b.id != id);
        self.highlights.retain(|h| h.book_id != id);
        self.posts.retain(|p| p.book_id != id);
        self.plans.retain(|p| p.book_id != id);
        let rid = format!("plan-{id}");
        self.reminders.retain(|r| r.id != rid);
        for c in &mut self.collections {
            c.book_ids.retain(|b| b != id);
        }
    }

    pub fn add_highlight(&mut self, h: Highlight, chapter_title: &str, make_post: bool) {
        if (make_post || self.settings.auto_post_highlights)
            && let Some(book) = self.book(&h.book_id)
        {
            let p = crate::posts::from_highlight(book, &h, chapter_title);
            self.posts.insert(0, p);
        }
        self.highlights.push(h);
    }

    pub fn highlight_mut(&mut self, id: &str) -> Option<&mut Highlight> {
        self.highlights.iter_mut().find(|h| h.id == id)
    }

    pub fn delete_highlight(&mut self, id: &str) {
        self.highlights.retain(|h| h.id != id);
        self.posts.retain(|p| p.highlight_id.as_deref() != Some(id));
    }

    /// Sets the highlight's reaction; picking the current one clears it.
    pub fn react_highlight(&mut self, id: &str, emoji: &str) {
        if let Some(h) = self.highlight_mut(id) {
            h.reaction = if h.reaction.as_deref() == Some(emoji) {
                None
            } else {
                Some(emoji.to_string())
            };
        }
    }

    pub fn post_from_highlight(&mut self, id: &str, chapter_title: &str) {
        let Some(h) = self.highlights.iter().find(|h| h.id == id).cloned() else {
            return;
        };
        let Some(book) = self.book(&h.book_id) else {
            return;
        };
        let p = crate::posts::from_highlight(book, &h, chapter_title);
        self.posts.retain(|q| q.id != p.id);
        self.posts.insert(0, p);
    }

    pub fn create_collection(&mut self, name: &str, book_ids: Vec<String>) -> String {
        let id = new_id();
        let mut ids = Vec::new();
        for b in book_ids {
            if !ids.contains(&b) {
                ids.push(b);
            }
        }
        let name = if name.trim().is_empty() {
            "Untitled".to_string()
        } else {
            name.trim().to_string()
        };
        self.collections.push(BookCollection {
            id: id.clone(),
            name,
            book_ids: ids,
            created_at: now_ms(),
            ..Default::default()
        });
        id
    }

    pub fn react_post(&mut self, id: &str, emoji: &str) {
        if let Some(p) = self.posts.iter_mut().find(|p| p.id == id) {
            toggle_reaction(&mut p.reactions, &mut p.my_reaction, emoji);
        }
    }
}

fn toggle_reaction(counts: &mut BTreeMap<String, i32>, mine: &mut Option<String>, emoji: &str) {
    if let Some(old) = mine.take() {
        let n = counts.get(&old).copied().unwrap_or(1) - 1;
        if n <= 0 {
            counts.remove(&old);
        } else {
            counts.insert(old.clone(), n);
        }
        if old == emoji {
            return;
        }
    }
    *counts.entry(emoji.to_string()).or_default() += 1;
    *mine = Some(emoji.to_string());
}

/// "<1 min", "42 min", "1h 4m".
pub fn format_duration(ms: i64) -> String {
    let min = ms / 60_000;
    match min {
        0 if ms > 0 => "<1 min".into(),
        0 => "0 min".into(),
        1..=59 => format!("{min} min"),
        _ => format!("{}h {}m", min / 60, min % 60),
    }
}

/// Days in a row, ending today or yesterday, with any reading.
pub fn reading_streak(log: &BTreeMap<String, i64>, today: i64) -> i32 {
    let read = |d: i64| log.get(&dates::key(d)).copied().unwrap_or(0) > 0;
    let mut day = if read(today) { today } else { today - 1 };
    let mut n = 0;
    while read(day) {
        n += 1;
        day -= 1;
    }
    n
}

/// "1 book", "3 books".
pub fn plural(n: usize, word: &str) -> String {
    if n == 1 {
        format!("1 {word}")
    } else {
        format!("{n} {word}s")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn titles_from_file_names() {
        assert_eq!(
            title_from_file_name("the_art-of_war (z-lib.org).epub"),
            "The Art of War"
        );
        assert_eq!(title_from_file_name("Meditations.pdf"), "Meditations");
    }

    #[test]
    fn reactions_toggle() {
        let mut d = LibraryData::default();
        d.books.push(Book {
            id: "b".into(),
            ..Default::default()
        });
        d.react_book("b", "🔥");
        d.react_book("b", "💡");
        assert_eq!(d.books[0].reactions.get("💡"), Some(&1));
        assert_eq!(d.books[0].reactions.get("🔥"), None);
        d.react_book("b", "💡");
        assert!(d.books[0].reactions.is_empty() && d.books[0].my_reaction.is_none());
    }

    #[test]
    fn goal_and_streak() {
        let mut d = LibraryData::default();
        d.settings.daily_goal_min = 1;
        d.books.push(Book {
            id: "b".into(),
            ..Default::default()
        });
        assert_eq!(d.add_reading_time("b", 30_000, "2026-10-27"), None);
        assert_eq!(d.add_reading_time("b", 40_000, "2026-10-27"), Some(1));
        assert_eq!(d.add_reading_time("b", 40_000, "2026-10-27"), None);
        d.reading_log.insert("2026-10-26".into(), 5);
        assert_eq!(
            reading_streak(&d.reading_log, dates::parse("2026-10-27").unwrap()),
            2
        );
        assert_eq!(format_duration(64 * 60_000), "1h 4m");
    }
}
