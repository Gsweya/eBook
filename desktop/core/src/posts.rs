//! Feed posts from highlights and from chapter text (extractive, on-device): a port of the Android
//! `PostGenerator`, producing the same post ids so a book's posts aren't duplicated across devices.

use std::collections::{HashMap, HashSet};

use crate::model::*;

const STOP: &str = "a about above after again against all am an and any are as at be because been before being below \
between both but by can could did do does doing down during each few for from further had has have \
having he her here hers herself him himself his how i if in into is it its itself just me more most my \
myself no nor not now of off on once only or other our ours ourselves out over own same she should so \
some such than that the their theirs them themselves then there these they this those through to too \
under until up very was we were what when where which while who whom why will with would you your \
yours yourself yourselves also one two may might must said says like even much many us upon";

/// Front and back matter that makes poor posts.
const MATTER: &[&str] = &[
    "praise",
    "contents",
    "table of contents",
    "copyright",
    "dedication",
    "acknowledg",
    "index",
    "notes",
    "endnotes",
    "about the author",
    "about the publisher",
    "also by",
    "title page",
    "cover",
    "bibliography",
    "epigraph",
    "further reading",
    "credits",
    "newsletter",
    "sign up",
    "front matter",
];

fn is_matter(ch: &Chapter) -> bool {
    let t = ch.title.trim().to_lowercase();
    MATTER.iter().any(|m| t.starts_with(m))
        || ch
            .blocks
            .iter()
            .map(|b| {
                if b.kind == PARAGRAPH {
                    b.text.chars().count()
                } else {
                    0
                }
            })
            .sum::<usize>()
            < 3500
}

/// Splits at whitespace that follows . ! or ? (and maybe a closing quote) and precedes a capital letter (maybe
/// after an opening quote).
pub fn sentences(text: &str) -> Vec<String> {
    let chars: Vec<char> = text.chars().collect();
    let mut out = Vec::new();
    let mut start = 0;
    let mut i = 0;
    while i < chars.len() {
        if chars[i].is_whitespace() && i > 0 {
            let mut p = i - 1;
            if "\"”’)".contains(chars[p]) && p > 0 {
                p -= 1;
            }
            let ends = ".!?".contains(chars[p]);
            let mut j = i;
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            let mut k = j;
            if k < chars.len() && "\"“‘(".contains(chars[k]) {
                k += 1;
            }
            if ends && k < chars.len() && chars[k].is_ascii_uppercase() {
                let s: String = chars[start..i].iter().collect();
                if !s.trim().is_empty() {
                    out.push(s.trim().to_string());
                }
                start = j;
                i = j;
                continue;
            }
        }
        i += 1;
    }
    let s: String = chars[start..].iter().collect();
    if !s.trim().is_empty() {
        out.push(s.trim().to_string());
    }
    out
}

fn tokens(s: &str) -> Vec<String> {
    s.to_lowercase()
        .split(|c: char| !(c.is_ascii_alphabetic() || c == '\''))
        .filter(|w| !w.is_empty())
        .map(String::from)
        .collect()
}

fn words(s: &str, stop: &HashSet<&str>) -> Vec<String> {
    tokens(s)
        .into_iter()
        .filter(|w| w.len() > 3 && !stop.contains(w.as_str()))
        .collect()
}

fn has_cue(s: &str) -> bool {
    let t = tokens(s);
    t.iter().any(|w| {
        matches!(
            w.as_str(),
            "is" | "means" | "important" | "must" | "never" | "always" | "because"
        )
    }) || t.windows(2).any(|w| w[0] == "the" && w[1] == "key")
}

struct Candidate {
    text: String,
    score: f64,
}

/// Ranked candidate sentences per chapter, scored by book-wide content-word frequency.
fn rank(content: &BookContent) -> Vec<Vec<Candidate>> {
    let stop: HashSet<&str> = STOP.split_whitespace().collect();
    let mut freq: HashMap<String, u32> = HashMap::new();
    for c in &content.chapters {
        for b in c.blocks.iter().filter(|b| b.kind == PARAGRAPH) {
            for w in words(&b.text, &stop) {
                *freq.entry(w).or_default() += 1;
            }
        }
    }
    content
        .chapters
        .iter()
        .map(|ch| {
            let mut v: Vec<Candidate> = ch
                .blocks
                .iter()
                .filter(|b| b.kind == PARAGRAPH)
                .flat_map(|b| sentences(&b.text))
                .filter(|s| {
                    let n = s.chars().count();
                    (70..=260).contains(&n)
                        && !s.contains("http")
                        && s.chars().filter(|&c| c == '"' || c == '“').count() < 3
                })
                .map(|s| {
                    let ws: HashSet<String> = words(&s, &stop).into_iter().collect();
                    let base = ws
                        .iter()
                        .map(|w| (*freq.get(w).unwrap_or(&0) as f64).sqrt())
                        .sum::<f64>()
                        / (ws.len() as f64 + 4.0).powf(0.6);
                    let cue = if has_cue(&s) { 1.15 } else { 1.0 };
                    Candidate {
                        text: s,
                        score: base * cue,
                    }
                })
                .collect();
            v.sort_by(|a, b| {
                b.score
                    .partial_cmp(&a.score)
                    .unwrap_or(std::cmp::Ordering::Equal)
            });
            v
        })
        .collect()
}

/// Marks mid-sentence selections with ellipses so the quote reads cleanly on its own.
fn tidy_quote(raw: &str) -> String {
    let mut t = raw
        .trim()
        .trim_matches(|c| ",;:—-".contains(c))
        .trim()
        .to_string();
    if t.chars().next().is_some_and(|c| c.is_lowercase()) {
        t = format!("…{t}");
    }
    if t.chars().last().is_some_and(|c| !".!?\"”’)…".contains(c)) {
        t.push('…');
    }
    t
}

pub fn from_highlight(book: &Book, h: &Highlight, chapter_title: &str) -> Post {
    let mut slides = vec![tidy_quote(&h.text)];
    if let Some(n) = h.note.as_ref().filter(|n| !n.trim().is_empty()) {
        slides.push(n.clone());
    }
    Post {
        id: format!("p-{}", h.id),
        book_id: book.id.clone(),
        kind: PostKind::QUOTE,
        slides,
        chapter_title: chapter_title.to_string(),
        chapter: h.chapter,
        highlight_id: Some(h.id.clone()),
        style: (h.color + 1) % 6,
        ..Default::default()
    }
}

/// Key-idea posts (single slide) plus chapter-recap carousels.
pub fn from_book(
    book: &Book,
    content: &BookContent,
    ideas_per_chapter: usize,
    recaps: bool,
) -> Vec<Post> {
    let ranked = rank(content);
    let now = now_ms();
    let mut out: Vec<Post> = Vec::new();
    for (ci, ch) in content.chapters.iter().enumerate() {
        let cands = &ranked[ci];
        if cands.is_empty() || is_matter(ch) {
            continue;
        }
        for (k, c) in cands.iter().take(ideas_per_chapter).enumerate() {
            let created = now - out.len() as i64;
            out.push(Post {
                id: format!("i-{}-{ci}-{k}", book.id),
                book_id: book.id.clone(),
                kind: PostKind::IDEA,
                slides: vec![c.text.clone()],
                chapter_title: ch.title.clone(),
                chapter: ci as i32,
                style: ((ci + k) % 6) as i32,
                created_at: created,
                ..Default::default()
            });
        }
        if recaps && cands.len() >= 4 {
            // Keep the top sentences in reading order so the carousel reads like a summary.
            let all: Vec<String> = ch
                .blocks
                .iter()
                .filter(|b| b.kind == PARAGRAPH)
                .flat_map(|b| sentences(&b.text))
                .collect();
            let mut top: Vec<&str> = cands.iter().take(4).map(|c| c.text.as_str()).collect();
            top.sort_by_key(|p| all.iter().position(|s| s == p).unwrap_or(usize::MAX));
            let mut slides = vec![ch.title.clone()];
            slides.extend(top.into_iter().map(String::from));
            let created = now - out.len() as i64;
            out.push(Post {
                id: format!("r-{}-{ci}", book.id),
                book_id: book.id.clone(),
                kind: PostKind::RECAP,
                slides,
                chapter_title: ch.title.clone(),
                chapter: ci as i32,
                style: ((ci + 3) % 6) as i32,
                created_at: created,
                ..Default::default()
            });
        }
    }
    out
}

/// Interleaves new posts by book so the feed doesn't show one book for 30 posts in a row, and puts them ahead
/// of the existing ones. Returns how many were new.
pub fn add_posts(data: &mut LibraryData, fresh: Vec<Post>) -> usize {
    let existing: HashSet<String> = data.posts.iter().map(|p| p.id.clone()).collect();
    let mut seen = HashSet::new();
    let mut groups: Vec<(String, std::collections::VecDeque<Post>)> = Vec::new();
    for p in fresh {
        if existing.contains(&p.id) || !seen.insert(p.id.clone()) {
            continue;
        }
        match groups.iter_mut().find(|(b, _)| *b == p.book_id) {
            Some((_, g)) => g.push_back(p),
            None => groups.push((p.book_id.clone(), std::collections::VecDeque::from([p]))),
        }
    }
    let mut mixed = Vec::new();
    while groups.iter().any(|(_, g)| !g.is_empty()) {
        for (_, g) in groups.iter_mut() {
            if let Some(p) = g.pop_front() {
                mixed.push(p);
            }
        }
    }
    let n = mixed.len();
    mixed.append(&mut data.posts);
    data.posts = mixed;
    n
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_sentences() {
        let s = sentences("He left. “Why?” she asked. Then e.g. this. And done");
        assert_eq!(
            s,
            vec![
                "He left.",
                "“Why?” she asked.",
                "Then e.g. this.",
                "And done"
            ]
        );
    }

    #[test]
    fn tidies_quotes() {
        assert_eq!(tidy_quote("and so it goes, "), "…and so it goes…");
        assert_eq!(tidy_quote("It goes."), "It goes.");
    }
}
