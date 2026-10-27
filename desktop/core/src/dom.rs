//! A small, forgiving XML/XHTML tree: enough DOM to read EPUB packages and chapters. It recovers from the
//! usual sloppiness in real books (unclosed void tags, mismatched end tags, HTML entities in XHTML) instead of
//! failing the whole import.

use quick_xml::Reader;
use quick_xml::events::Event;

#[derive(Debug, Clone)]
pub enum Node {
    El(Element),
    Text(String),
}

#[derive(Debug, Clone, Default)]
pub struct Element {
    /// Local name, lower-cased ("dc:title" becomes "title").
    pub name: String,
    /// Qualified attribute names as written ("epub:type", "xlink:href").
    pub attrs: Vec<(String, String)>,
    pub children: Vec<Node>,
}

const VOID: &[&str] = &[
    "br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source",
];

/// Elements the text of which ends with a line break in rendered HTML, so `text()` puts a space after them.
const BLOCKISH: &[&str] = &[
    "p",
    "div",
    "li",
    "ul",
    "ol",
    "blockquote",
    "pre",
    "section",
    "article",
    "table",
    "tr",
    "td",
    "th",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
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
    "br",
];

pub fn parse(src: &str) -> Element {
    let mut reader = Reader::from_str(src);
    {
        let c = reader.config_mut();
        c.check_end_names = false;
        c.trim_text(false);
        c.allow_unmatched_ends = true;
    }
    let mut stack: Vec<Element> = vec![Element {
        name: "#root".into(),
        ..Default::default()
    }];
    // Stop at the first error but keep everything read so far: a broken tail shouldn't lose the chapter.
    while let Ok(ev) = reader.read_event() {
        match ev {
            Event::Start(e) => {
                let el = element(&e);
                if VOID.contains(&el.name.as_str()) {
                    push_child(&mut stack, Node::El(el));
                } else {
                    stack.push(el);
                }
            }
            Event::Empty(e) => push_child(&mut stack, Node::El(element(&e))),
            Event::End(e) => {
                let name = local(e.name().as_ref());
                // Close up to the matching open element; ignore end tags that match nothing.
                if let Some(pos) = stack.iter().rposition(|el| el.name == name)
                    && pos > 0
                {
                    while stack.len() > pos {
                        let el = stack.pop().unwrap();
                        push_child(&mut stack, Node::El(el));
                    }
                }
            }
            Event::Text(t) => {
                push_text(&mut stack, &decode_entities(&t));
            }
            Event::CData(t) => push_text(&mut stack, &t),
            Event::GeneralRef(r) => {
                let name = r.to_string();
                push_text(
                    &mut stack,
                    &entity(&name).unwrap_or_else(|| format!("&{name};")),
                );
            }
            Event::Eof => break,
            _ => {}
        }
    }
    while stack.len() > 1 {
        let el = stack.pop().unwrap();
        push_child(&mut stack, Node::El(el));
    }
    stack.pop().unwrap()
}

fn push_child(stack: &mut [Element], n: Node) {
    stack.last_mut().unwrap().children.push(n);
}

fn push_text(stack: &mut [Element], s: &str) {
    if s.is_empty() {
        return;
    }
    let top = stack.last_mut().unwrap();
    if let Some(Node::Text(prev)) = top.children.last_mut() {
        prev.push_str(s);
    } else {
        top.children.push(Node::Text(s.to_string()));
    }
}

fn local(qname: &str) -> String {
    qname.rsplit(':').next().unwrap_or("").to_ascii_lowercase()
}

fn element(e: &quick_xml::events::BytesStart) -> Element {
    let attrs = e
        .attributes()
        .with_checks(false)
        .flatten()
        .map(|a| {
            let k = a.key.as_ref().to_string();
            let v = decode_entities(&a.value);
            (k, v)
        })
        .collect();
    Element {
        name: local(e.name().as_ref()),
        attrs,
        children: Vec::new(),
    }
}

/// Replaces `&name;` and `&#n;` references; unknown ones are left as written.
pub fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        let tail = &rest[i + 1..];
        match tail.find(';').filter(|&j| j > 0 && j <= 10) {
            Some(j) => match entity(&tail[..j]) {
                Some(v) => {
                    out.push_str(&v);
                    rest = &tail[j + 1..];
                }
                None => {
                    out.push('&');
                    rest = tail;
                }
            },
            None => {
                out.push('&');
                rest = tail;
            }
        }
    }
    out.push_str(rest);
    out
}

fn entity(name: &str) -> Option<String> {
    if let Some(num) = name.strip_prefix('#') {
        let code = match num.strip_prefix(['x', 'X']) {
            Some(hex) => u32::from_str_radix(hex, 16).ok()?,
            None => num.parse().ok()?,
        };
        return char::from_u32(code).map(String::from);
    }
    let c = match name {
        "amp" => "&",
        "lt" => "<",
        "gt" => ">",
        "quot" => "\"",
        "apos" => "'",
        "nbsp" => "\u{a0}",
        "shy" => "\u{ad}",
        "ensp" => "\u{2002}",
        "emsp" => "\u{2003}",
        "thinsp" => "\u{2009}",
        "zwnj" => "\u{200c}",
        "zwj" => "\u{200d}",
        "ndash" => "–",
        "mdash" => "—",
        "lsquo" => "‘",
        "rsquo" => "’",
        "sbquo" => "‚",
        "ldquo" => "“",
        "rdquo" => "”",
        "bdquo" => "„",
        "hellip" => "…",
        "laquo" => "«",
        "raquo" => "»",
        "lsaquo" => "‹",
        "rsaquo" => "›",
        "bull" => "•",
        "middot" => "·",
        "para" => "¶",
        "sect" => "§",
        "copy" => "©",
        "reg" => "®",
        "trade" => "™",
        "deg" => "°",
        "times" => "×",
        "divide" => "÷",
        "prime" => "′",
        "Prime" => "″",
        "dagger" => "†",
        "Dagger" => "‡",
        "iexcl" => "¡",
        "iquest" => "¿",
        "cent" => "¢",
        "pound" => "£",
        "euro" => "€",
        "yen" => "¥",
        "frac12" => "½",
        "frac14" => "¼",
        "frac34" => "¾",
        "sup1" => "¹",
        "sup2" => "²",
        "sup3" => "³",
        "aacute" => "á",
        "eacute" => "é",
        "iacute" => "í",
        "oacute" => "ó",
        "uacute" => "ú",
        "agrave" => "à",
        "egrave" => "è",
        "igrave" => "ì",
        "ograve" => "ò",
        "ugrave" => "ù",
        "acirc" => "â",
        "ecirc" => "ê",
        "icirc" => "î",
        "ocirc" => "ô",
        "ucirc" => "û",
        "auml" => "ä",
        "euml" => "ë",
        "iuml" => "ï",
        "ouml" => "ö",
        "uuml" => "ü",
        "yuml" => "ÿ",
        "atilde" => "ã",
        "otilde" => "õ",
        "ntilde" => "ñ",
        "ccedil" => "ç",
        "aring" => "å",
        "aelig" => "æ",
        "oslash" => "ø",
        "szlig" => "ß",
        "oelig" => "œ",
        "Aacute" => "Á",
        "Eacute" => "É",
        "Iacute" => "Í",
        "Oacute" => "Ó",
        "Uacute" => "Ú",
        "Agrave" => "À",
        "Egrave" => "È",
        "Auml" => "Ä",
        "Ouml" => "Ö",
        "Uuml" => "Ü",
        "Ntilde" => "Ñ",
        "Ccedil" => "Ç",
        "Aring" => "Å",
        "AElig" => "Æ",
        "Oslash" => "Ø",
        "OElig" => "Œ",
        _ => return None,
    };
    Some(c.to_string())
}

impl Element {
    pub fn attr(&self, key: &str) -> Option<&str> {
        self.attrs
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }

    /// The attribute, or "" when it's missing.
    pub fn a(&self, key: &str) -> &str {
        self.attr(key).unwrap_or("")
    }

    pub fn elements(&self) -> impl Iterator<Item = &Element> {
        self.children
            .iter()
            .filter_map(|n| if let Node::El(e) = n { Some(e) } else { None })
    }

    /// Every descendant element in document order.
    pub fn descendants(&self) -> Vec<&Element> {
        let mut out = Vec::new();
        fn go<'a>(e: &'a Element, out: &mut Vec<&'a Element>) {
            for c in e.elements() {
                out.push(c);
                go(c, out);
            }
        }
        go(self, &mut out);
        out
    }

    pub fn find_all(&self, name: &str) -> Vec<&Element> {
        self.descendants()
            .into_iter()
            .filter(|e| e.name == name)
            .collect()
    }

    pub fn find(&self, name: &str) -> Option<&Element> {
        self.descendants().into_iter().find(|e| e.name == name)
    }

    pub fn has_descendant(&self, names: &[&str]) -> bool {
        self.elements()
            .any(|c| names.contains(&c.name.as_str()) || c.has_descendant(names))
    }

    /// Text with whitespace collapsed and trimmed, a space standing in for block boundaries and `<br>`.
    pub fn text(&self) -> String {
        let mut raw = String::new();
        self.collect_text(&mut raw);
        normalize(&raw)
    }

    fn collect_text(&self, out: &mut String) {
        if self.name == "script" || self.name == "style" {
            return;
        }
        for c in &self.children {
            match c {
                Node::Text(t) => out.push_str(t),
                Node::El(e) => {
                    e.collect_text(out);
                    if BLOCKISH.contains(&e.name.as_str()) {
                        out.push(' ');
                    }
                }
            }
        }
    }

    /// Text exactly as written, for `<pre>`.
    pub fn whole_text(&self) -> String {
        let mut out = String::new();
        fn go(e: &Element, out: &mut String) {
            for c in &e.children {
                match c {
                    Node::Text(t) => out.push_str(t),
                    Node::El(el) if el.name == "br" => out.push('\n'),
                    Node::El(el) => go(el, out),
                }
            }
        }
        go(self, &mut out);
        out
    }
}

/// Collapses runs of whitespace (but not no-break spaces) to one space and trims the ends.
pub fn normalize(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut space = false;
    for c in s.chars() {
        if c.is_whitespace() && c != '\u{a0}' {
            space = true;
        } else {
            if space && !out.is_empty() {
                out.push(' ');
            }
            space = false;
            out.push(c);
        }
    }
    // Like Kotlin's trim(), which the Android parser uses: no-break spaces at the ends go too.
    let t = out.trim();
    if t.len() == out.len() {
        out
    } else {
        t.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forgiving_parse() {
        let root = parse(
            "<html><body><p>One &amp; <b>two</b>&nbsp;three<br>four</p><p>Caf&eacute;</body></html>",
        );
        let body = root.find("body").unwrap();
        let ps: Vec<_> = body.find_all("p");
        assert_eq!(ps.len(), 2);
        assert_eq!(ps[0].text(), "One & two\u{a0}three four");
        assert_eq!(ps[1].text(), "Café");
    }
}
