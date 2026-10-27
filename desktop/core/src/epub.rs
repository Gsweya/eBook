//! Minimal EPUB 2/3 reader: metadata, spine, TOC titles, text blocks and images. A port of the Android
//! `EpubParser`, producing the same `BookContent` for the same book.

use std::collections::HashMap;
use std::fs::File;
use std::io::Read;
use std::path::Path;

use crate::dom::{self, Element, Node};
use crate::model::{Block, BookContent, Chapter, HEADING, PARAGRAPH};

pub struct EpubResult {
    pub title: String,
    pub author: String,
    pub content: BookContent,
    pub cover: Option<Vec<u8>>,
}

const HEADINGS: &[&str] = &["h1", "h2", "h3", "h4", "h5", "h6"];
const BLOCKS: &[&str] = &[
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "div",
    "p",
    "li",
    "ul",
    "ol",
    "blockquote",
    "pre",
    "section",
    "article",
    "table",
    "tbody",
    "thead",
    "tr",
    "td",
    "th",
    "figure",
    "figcaption",
    "aside",
    "header",
    "footer",
    "nav",
    "dl",
    "dt",
    "dd",
    "body",
    "main",
    "hr",
    "center",
    "svg",
];

struct Item {
    id: String,
    path: String,
    media: String,
    props: String,
}

/// Parses the EPUB at `file`, writing its images into `images_dir`.
pub fn parse(file: &Path, images_dir: &Path) -> Result<EpubResult, String> {
    let f = File::open(file).map_err(|e| e.to_string())?;
    let mut zip =
        zip::ZipArchive::new(f).map_err(|_| "Not an EPUB (not a zip file)".to_string())?;
    let mut read = |path: &str| -> Option<Vec<u8>> {
        let mut e = zip.by_name(path).ok()?;
        let mut buf = Vec::with_capacity(e.size() as usize);
        e.read_to_end(&mut buf).ok()?;
        Some(buf)
    };
    fn text(b: Vec<u8>) -> String {
        String::from_utf8(b).unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned())
    }

    let container = dom::parse(&text(
        read("META-INF/container.xml").ok_or("Not an EPUB (missing container.xml)")?,
    ));
    let opf_path = container
        .find("rootfile")
        .map(|r| r.a("full-path").to_string())
        .ok_or("No OPF")?;
    let opf_dir = parent(&opf_path);
    let opf = dom::parse(&text(read(&opf_path).ok_or("Missing OPF")?));

    let meta = |name: &str| opf.find(name).map(|e| e.text()).unwrap_or_default();
    let title = meta("title");
    let author = meta("creator");

    let items: Vec<Item> = opf
        .find_all("item")
        .into_iter()
        .map(|e| Item {
            id: e.a("id").to_string(),
            path: resolve(&opf_dir, e.a("href")),
            media: e.a("media-type").to_string(),
            props: e.a("properties").to_string(),
        })
        .collect();
    let by_id: HashMap<&str, &Item> = items.iter().map(|i| (i.id.as_str(), i)).collect();

    // Cover image
    let cover_item = items
        .iter()
        .find(|i| i.props.split(' ').any(|p| p == "cover-image"))
        .or_else(|| {
            opf.find_all("meta")
                .into_iter()
                .find(|m| m.a("name") == "cover")
                .and_then(|m| by_id.get(m.a("content")).copied())
        })
        .or_else(|| {
            items
                .iter()
                .find(|i| i.media.starts_with("image") && i.id.to_lowercase().contains("cover"))
        });
    let cover = cover_item
        .filter(|i| i.media.starts_with("image"))
        .and_then(|i| read(&i.path));

    // TOC titles keyed by document path (fragment stripped), in TOC order.
    let mut toc: Vec<(String, String)> = Vec::new();
    let add_toc = |toc: &mut Vec<(String, String)>, p: String, t: String| {
        if !t.is_empty() && !toc.iter().any(|(k, _)| *k == p) {
            toc.push((p, t));
        }
    };
    if let Some(nav) = items
        .iter()
        .find(|i| i.props.split(' ').any(|p| p == "nav"))
        && let Some(html) = read(&nav.path)
    {
        let doc = dom::parse(&text(html));
        let nav_dir = parent(&nav.path);
        let navs = doc.find_all("nav");
        let toc_nav = navs
            .iter()
            .find(|n| n.a("epub:type") == "toc")
            .or(navs.first());
        if let Some(n) = toc_nav {
            for a in n.find_all("a") {
                if let Some(href) = a.attr("href") {
                    add_toc(&mut toc, resolve(&nav_dir, before_hash(href)), a.text());
                }
            }
        }
    }
    if toc.is_empty() {
        let ncx_id = opf
            .find("spine")
            .map(|s| s.a("toc").to_string())
            .unwrap_or_default();
        let ncx = by_id
            .get(ncx_id.as_str())
            .copied()
            .or_else(|| items.iter().find(|i| i.media == "application/x-dtbncx+xml"));
        if let Some(n) = ncx
            && let Some(x) = read(&n.path)
        {
            let doc = dom::parse(&text(x));
            let ncx_dir = parent(&n.path);
            for np in doc.find_all("navpoint") {
                let label = np.find("text").map(|t| t.text()).unwrap_or_default();
                if let Some(src) = np.find("content").and_then(|c| c.attr("src")) {
                    add_toc(&mut toc, resolve(&ncx_dir, before_hash(src)), label);
                }
            }
        }
    }
    let toc_title = |p: &str| toc.iter().find(|(k, _)| k == p).map(|(_, t)| t.clone());

    std::fs::create_dir_all(images_dir).map_err(|e| e.to_string())?;
    let mut saved: HashMap<String, Option<String>> = HashMap::new();
    let mut chapters: Vec<Chapter> = Vec::new();
    for r in opf.find_all("itemref") {
        let Some(item) = by_id.get(r.a("idref")) else {
            continue;
        };
        if !item.media.contains("html") {
            continue;
        }
        let Some(html) = read(&item.path) else {
            continue;
        };
        let doc = dom::parse(&text(html));
        let doc_dir = parent(&item.path);
        let body = doc.find("body").unwrap_or(&doc);
        let mut blocks = Vec::new();
        walk(body, &mut blocks, &mut |src: &str| {
            let path = resolve(&doc_dir, src);
            saved
                .entry(path.clone())
                .or_insert_with(|| {
                    let bytes = read(&path)?;
                    let name: String = path
                        .replace('/', "_")
                        .chars()
                        .filter(|c| c.is_ascii_alphanumeric() || "._-".contains(*c))
                        .collect();
                    std::fs::write(images_dir.join(&name), bytes).ok()?;
                    Some(name)
                })
                .clone()
        });
        if blocks.is_empty() {
            continue;
        }
        // Spine documents without a TOC entry or heading are continuations of the previous chapter.
        if !toc.is_empty()
            && toc_title(&item.path).is_none()
            && !blocks.iter().any(|b| b.kind == HEADING)
            && let Some(prev) = chapters.last_mut()
        {
            prev.blocks.extend(blocks);
            continue;
        }
        let ch_title = toc_title(&item.path)
            .or_else(|| {
                blocks
                    .iter()
                    .find(|b| b.kind == HEADING)
                    .map(|b| b.text.chars().take(80).collect())
            })
            .unwrap_or_else(|| format!("Section {}", chapters.len() + 1));
        chapters.push(Chapter {
            title: ch_title,
            blocks,
            ..Default::default()
        });
    }
    Ok(EpubResult {
        title,
        author,
        content: BookContent { chapters },
        cover,
    })
}

fn is_img(name: &str) -> bool {
    name == "img" || name == "image"
}

fn walk(el: &Element, out: &mut Vec<Block>, image: &mut dyn FnMut(&str) -> Option<String>) {
    let tag = el.name.as_str();
    if tag == "script" || tag == "style" {
        return;
    }
    if tag == "img" {
        if let Some(n) = image(el.a("src")) {
            out.push(Block::image(n));
        }
        return;
    }
    if tag == "image" {
        let href = el
            .attr("xlink:href")
            .filter(|h| !h.is_empty())
            .unwrap_or(el.a("href"));
        if let Some(n) = image(href) {
            out.push(Block::image(n));
        }
        return;
    }
    if HEADINGS.contains(&tag) {
        for d in el.descendants() {
            if is_img(&d.name) {
                walk(d, out, image);
            }
        }
        let t = el.text();
        if !t.is_empty() {
            out.push(Block::heading(t, tag[1..].parse().unwrap_or(1)));
        }
        return;
    }
    if tag == "pre" {
        let t = el.whole_text().trim().to_string();
        if !t.is_empty() {
            out.push(Block::para(t));
        }
        return;
    }
    if BLOCKS.contains(&tag)
        && !el
            .elements()
            .any(|c| BLOCKS.contains(&c.name.as_str()) || is_img(&c.name))
    {
        let t = el.text();
        if !t.is_empty() {
            out.push(Block {
                kind: PARAGRAPH,
                text: t,
                ..Default::default()
            });
        }
        return;
    }
    // Mixed content: inline runs become paragraphs, block children recurse.
    let mut buf = String::new();
    let flush = |buf: &mut String, out: &mut Vec<Block>| {
        let t = dom::normalize(buf);
        if !t.is_empty() {
            out.push(Block::para(t));
        }
        buf.clear();
    };
    for node in &el.children {
        match node {
            Node::Text(t) => buf.push_str(t),
            Node::El(c) => {
                let inline = !BLOCKS.contains(&c.name.as_str())
                    && !is_img(&c.name)
                    && !c.has_descendant(&["img", "image"]);
                if inline {
                    if c.name == "br" {
                        buf.push(' ');
                    } else {
                        buf.push_str(&c.text());
                    }
                } else {
                    flush(&mut buf, out);
                    walk(c, out, image);
                }
            }
        }
    }
    flush(&mut buf, out);
}

fn parent(path: &str) -> String {
    path.rsplit_once('/')
        .map(|(d, _)| d.to_string())
        .unwrap_or_default()
}

fn before_hash(href: &str) -> &str {
    href.split('#').next().unwrap_or("")
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            let hex = |c: u8| (c as char).to_digit(16);
            if let (Some(h), Some(l)) = (hex(b[i + 1]), hex(b[i + 2])) {
                out.push((h * 16 + l) as u8);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8(out).unwrap_or_else(|_| s.to_string())
}

/// Resolves `href` against `base_dir` inside the zip, folding "." and "..".
fn resolve(base_dir: &str, href: &str) -> String {
    let decoded = percent_decode(href);
    let joined = if base_dir.is_empty() {
        decoded
    } else {
        format!("{base_dir}/{decoded}")
    };
    let mut stack: Vec<&str> = Vec::new();
    for p in joined.split('/') {
        match p {
            "" | "." => {}
            ".." => {
                stack.pop();
            }
            _ => stack.push(p),
        }
    }
    stack.join("/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_paths() {
        assert_eq!(
            resolve("OEBPS/Text", "../Images/a%20b.jpg"),
            "OEBPS/Images/a b.jpg"
        );
        assert_eq!(resolve("", "ch1.xhtml"), "ch1.xhtml");
    }

    #[test]
    fn walks_mixed_content() {
        let doc = dom::parse(
            "<body><h2>Chapter <i>One</i></h2><div>Loose text <em>here</em><p>A para.</p>tail</div><pre>a\n  b</pre></body>",
        );
        let mut out = Vec::new();
        walk(doc.find("body").unwrap(), &mut out, &mut |_| None);
        let texts: Vec<_> = out.iter().map(|b| (b.kind, b.text.as_str())).collect();
        assert_eq!(
            texts,
            vec![
                (1, "Chapter One"),
                (0, "Loose text here"),
                (0, "A para."),
                (0, "tail"),
                (0, "a\n  b")
            ]
        );
    }
}
