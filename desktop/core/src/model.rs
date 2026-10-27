//! The library schema, field for field the same as the Android app's `Models.kt`, so `library.json`,
//! `content.json` and backups move between phone and desktop unchanged. Fields this version doesn't know
//! about are kept in `extra` and written back, so a newer phone's data survives a round trip.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

pub type Extra = Map<String, Value>;

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum BookFormat {
    #[default]
    EPUB,
    PDF,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct ReadingPosition {
    pub chapter: i32,
    /// Paragraph (block) index within the chapter.
    pub item: i32,
    /// Pixels scrolled past the top of that paragraph.
    pub offset: i32,
    pub pdf_page: i32,
    #[serde(flatten)]
    pub extra: Extra,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct Book {
    pub id: String,
    pub title: String,
    pub author: String,
    pub format: BookFormat,
    pub file_name: String,
    pub has_cover: bool,
    pub cover_color: i64,
    pub added_at: i64,
    pub last_opened_at: i64,
    pub chapter_count: i32,
    pub page_count: i32,
    pub progress: f32,
    pub position: ReadingPosition,
    /// Emoji -> count. The user's own pick is `my_reaction`.
    pub reactions: BTreeMap<String, i32>,
    pub my_reaction: Option<String>,
    /// For PDFs: whether reflowable text has been extracted into content.json.
    pub text_ready: bool,
    pub text_version: i32,
    pub backed_up_at: i64,
    /// Total active reading time in this book.
    pub reading_ms: i64,
    pub pages_read: i32,
    /// 1 to 5 stars; 0 means not rated.
    pub rating: i32,
    #[serde(flatten)]
    pub extra: Extra,
}

impl Default for Book {
    fn default() -> Self {
        Book {
            id: String::new(),
            title: String::new(),
            author: String::new(),
            format: BookFormat::EPUB,
            file_name: String::new(),
            has_cover: false,
            cover_color: DEFAULT_COVER_COLOR,
            added_at: now_ms(),
            last_opened_at: 0,
            chapter_count: 0,
            page_count: 0,
            progress: 0.0,
            position: ReadingPosition::default(),
            reactions: BTreeMap::new(),
            my_reaction: None,
            text_ready: true,
            text_version: 0,
            backed_up_at: 0,
            reading_ms: 0,
            pages_read: 0,
            rating: 0,
            extra: Extra::new(),
        }
    }
}

pub const DEFAULT_COVER_COLOR: i64 = 0xFF8F3D26;

/// A highlight's offsets are UTF-16 code units into the paragraph text, as on Android.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct Highlight {
    pub id: String,
    pub book_id: String,
    pub chapter: i32,
    pub block: i32,
    pub start: i32,
    pub end: i32,
    pub text: String,
    pub color: i32,
    pub note: Option<String>,
    pub created_at: i64,
    /// Last paragraph covered, with `end` an offset into it; -1 means the highlight stays within `block`.
    pub end_block: i32,
    /// 0 filled marker, 1 underline.
    pub style: i32,
    pub reaction: Option<String>,
    #[serde(flatten)]
    pub extra: Extra,
}

impl Default for Highlight {
    fn default() -> Self {
        Highlight {
            id: String::new(),
            book_id: String::new(),
            chapter: 0,
            block: 0,
            start: 0,
            end: 0,
            text: String::new(),
            color: 0,
            note: None,
            created_at: now_ms(),
            end_block: -1,
            style: 0,
            reaction: None,
            extra: Extra::new(),
        }
    }
}

impl Highlight {
    pub fn last_block(&self) -> i32 {
        if self.end_block < 0 {
            self.block
        } else {
            self.end_block
        }
    }

    pub fn covers(&self, b: i32) -> bool {
        (self.block..=self.last_block()).contains(&b)
    }

    /// UTF-16 range of this highlight inside paragraph `b`, whose text is `len` UTF-16 units long.
    pub fn range_in(&self, b: i32, len: i32) -> (i32, i32) {
        let s = if b == self.block {
            self.start.clamp(0, len)
        } else {
            0
        };
        let e = if b == self.last_block() {
            self.end.clamp(0, len)
        } else {
            len
        };
        (s, e.max(s))
    }
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum PostKind {
    #[default]
    QUOTE,
    IDEA,
    RECAP,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct Post {
    pub id: String,
    pub book_id: String,
    pub kind: PostKind,
    /// One entry per carousel slide.
    pub slides: Vec<String>,
    pub chapter_title: String,
    pub chapter: i32,
    pub highlight_id: Option<String>,
    pub style: i32,
    pub created_at: i64,
    pub liked: bool,
    pub saved: bool,
    pub reactions: BTreeMap<String, i32>,
    pub my_reaction: Option<String>,
    #[serde(flatten)]
    pub extra: Extra,
}

impl Default for Post {
    fn default() -> Self {
        Post {
            id: String::new(),
            book_id: String::new(),
            kind: PostKind::QUOTE,
            slides: Vec::new(),
            chapter_title: String::new(),
            chapter: 0,
            highlight_id: None,
            style: 0,
            created_at: now_ms(),
            liked: false,
            saved: false,
            reactions: BTreeMap::new(),
            my_reaction: None,
            extra: Extra::new(),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct ReaderSettings {
    pub font_size: f32,
    pub line_height: f32,
    pub theme: i32,
    pub font: String,
    pub bold: bool,
    pub justify: bool,
    pub margin: i32,
    pub auto_post_highlights: bool,
    /// App accent palette id (see `theme::PALETTES`).
    pub accent: String,
    /// 0 follow system, 1 light, 2 dark.
    pub appearance: i32,
    /// Pure black backgrounds in dark mode.
    pub amoled: bool,
    /// Style for new highlights: 0 filled marker, 1 underline.
    pub highlight_style: i32,
    pub nav_style: i32,
    pub nav_icons: i32,
    pub profile_name: String,
    /// Changes whenever the profile picture changes; 0 means none.
    pub avatar_version: i64,
    /// Daily reading goal in minutes; 0 means no goal.
    pub daily_goal_min: i32,
    /// 0 scroll through the whole chapter, 1 one page at a time.
    pub page_mode: i32,
    pub tracker: bool,
    pub tracker_unit: i32,
    pub tracker_color: i32,
    pub tracker_position: i32,
    #[serde(flatten)]
    pub extra: Extra,
}

impl Default for ReaderSettings {
    fn default() -> Self {
        ReaderSettings {
            font_size: 19.0,
            line_height: 1.5,
            theme: 0,
            font: "literata".into(),
            bold: false,
            justify: false,
            margin: 24,
            auto_post_highlights: false,
            accent: "amber".into(),
            appearance: 0,
            amoled: false,
            highlight_style: 0,
            nav_style: 0,
            nav_icons: 0,
            profile_name: String::new(),
            avatar_version: 0,
            daily_goal_min: 0,
            page_mode: 0,
            tracker: false,
            tracker_unit: 0,
            tracker_color: -1,
            tracker_position: 0,
            extra: Extra::new(),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(default, rename_all = "camelCase")]
pub struct LibraryData {
    pub books: Vec<Book>,
    pub highlights: Vec<Highlight>,
    pub posts: Vec<Post>,
    pub settings: ReaderSettings,
    pub seeded: bool,
    pub starter_added: bool,
    pub last_backup_at: i64,
    /// Active reading time per local day ("yyyy-MM-dd"), across all books.
    pub reading_log: BTreeMap<String, i64>,
    /// Active reading time per local day, per book id.
    pub book_days: BTreeMap<String, BTreeMap<String, i64>>,
    pub reminders: Vec<Reminder>,
    pub plans: Vec<ReadingPlan>,
    pub goal_reached_day: String,
    pub collections: Vec<BookCollection>,
    /// Pages read per local day, across all books.
    pub pages_log: BTreeMap<String, i32>,
    pub onboarded: bool,
    #[serde(flatten)]
    pub extra: Extra,
}

impl LibraryData {
    pub fn book(&self, id: &str) -> Option<&Book> {
        self.books.iter().find(|b| b.id == id)
    }

    pub fn book_mut(&mut self, id: &str) -> Option<&mut Book> {
        self.books.iter_mut().find(|b| b.id == id)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(default, rename_all = "camelCase")]
pub struct BookCollection {
    pub id: String,
    pub name: String,
    pub book_ids: Vec<String>,
    pub created_at: i64,
    #[serde(flatten)]
    pub extra: Extra,
}

/// A reading reminder at `hour`:`minute` on `days` (ISO day numbers, Monday = 1).
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default, rename_all = "camelCase")]
pub struct Reminder {
    pub id: String,
    pub hour: i32,
    pub minute: i32,
    pub days: Vec<i32>,
    pub enabled: bool,
    pub book_id: Option<String>,
    #[serde(flatten)]
    pub extra: Extra,
}

impl Default for Reminder {
    fn default() -> Self {
        Reminder {
            id: String::new(),
            hour: 20,
            minute: 0,
            days: (1..=7).collect(),
            enabled: true,
            book_id: None,
            extra: Extra::new(),
        }
    }
}

/// Goal to finish `book_id` by `target_date`; dates are "yyyy-MM-dd".
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(default, rename_all = "camelCase")]
pub struct ReadingPlan {
    pub book_id: String,
    pub target_date: String,
    pub start_date: String,
    pub start_progress: f32,
    #[serde(flatten)]
    pub extra: Extra,
}

pub const PARAGRAPH: i32 = 0;
pub const HEADING: i32 = 1;
pub const IMAGE: i32 = 2;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(default)]
pub struct Block {
    /// 0 = paragraph, 1 = heading, 2 = image
    #[serde(rename = "type")]
    pub kind: i32,
    pub text: String,
    pub level: i32,
    pub src: Option<String>,
    /// Source page for PDF-derived text, -1 otherwise.
    pub page: i32,
}

impl Default for Block {
    fn default() -> Self {
        Block {
            kind: PARAGRAPH,
            text: String::new(),
            level: 0,
            src: None,
            page: -1,
        }
    }
}

impl Block {
    pub fn para(text: impl Into<String>) -> Self {
        Block {
            text: text.into(),
            ..Default::default()
        }
    }

    pub fn heading(text: impl Into<String>, level: i32) -> Self {
        Block {
            kind: HEADING,
            text: text.into(),
            level,
            ..Default::default()
        }
    }

    pub fn image(src: impl Into<String>) -> Self {
        Block {
            kind: IMAGE,
            src: Some(src.into()),
            ..Default::default()
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Chapter {
    pub title: String,
    pub blocks: Vec<Block>,
    pub start_page: i32,
}

impl Default for Chapter {
    fn default() -> Self {
        Chapter {
            title: String::new(),
            blocks: Vec::new(),
            start_page: -1,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(default)]
pub struct BookContent {
    pub chapters: Vec<Chapter>,
}

pub const REACTION_EMOJIS: [&str; 6] = ["❤️", "🔥", "💡", "🤯", "😂", "😢"];

/// Length of `s` in UTF-16 code units, the unit highlight offsets are stored in.
pub fn utf16_len(s: &str) -> i32 {
    s.encode_utf16().count() as i32
}

/// Character (Unicode scalar) offset for UTF-16 offset `u` in `s`.
pub fn utf16_to_char(s: &str, u: i32) -> i32 {
    let mut units = 0;
    for (i, c) in s.chars().enumerate() {
        if units >= u {
            return i as i32;
        }
        units += c.len_utf16() as i32;
    }
    s.chars().count() as i32
}

/// UTF-16 offset for character offset `c` in `s`.
pub fn char_to_utf16(s: &str, c: i32) -> i32 {
    s.chars()
        .take(c.max(0) as usize)
        .map(|ch| ch.len_utf16() as i32)
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn android_library_json_round_trips() {
        let json = r#"{"books":[{"id":"abc","title":"T","author":"A","format":"PDF","fileName":"book.pdf",
            "hasCover":true,"coverColor":4287577382,"addedAt":1,"lastOpenedAt":2,"chapterCount":3,"pageCount":4,
            "progress":0.5,"position":{"chapter":1,"item":2,"offset":3,"pdfPage":4},"reactions":{"🔥":1},
            "myReaction":"🔥","textReady":true,"textVersion":3,"backedUpAt":0,"readingMs":60000,"pagesRead":2,
            "rating":4,"futureField":[1,2]}],
            "highlights":[{"id":"h","bookId":"abc","chapter":0,"block":1,"start":2,"end":5,"text":"x","color":1,
            "note":null,"createdAt":9,"endBlock":-1,"style":0,"reaction":null}],
            "settings":{"fontSize":21.0,"accent":"jade","somethingNew":true},"readingLog":{"2026-10-01":5000}}"#;
        let d: LibraryData = serde_json::from_str(json).unwrap();
        assert_eq!(d.books[0].format, BookFormat::PDF);
        assert_eq!(d.books[0].cover_color, 0xFF8F3D26);
        assert_eq!(d.settings.font_size, 21.0);
        assert_eq!(d.settings.line_height, 1.5);
        let out = serde_json::to_string(&d).unwrap();
        assert!(out.contains("\"futureField\":[1,2]"));
        assert!(out.contains("\"somethingNew\":true"));
        assert!(out.contains("\"fileName\":\"book.pdf\""));
        assert!(out.contains("\"note\":null"));
    }

    #[test]
    fn utf16_offsets() {
        let s = "a😀b";
        assert_eq!(utf16_len(s), 4);
        assert_eq!(utf16_to_char(s, 3), 2);
        assert_eq!(char_to_utf16(s, 2), 3);
    }
}
