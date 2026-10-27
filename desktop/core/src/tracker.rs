//! The focus tracker's steps: the words or sentences of a paragraph, as UTF-16 ranges (the unit highlight
//! offsets use), matching the Android app's `trackerUnits`.

/// Ranges of `text` the tracker steps through: words (runs of non-space) or sentences (up to and including
/// `.`, `!`, `?` or `…` and any closing quotes), each without trailing spaces.
pub fn units(text: &str, sentences: bool) -> Vec<(i32, i32)> {
    let chars: Vec<char> = text.chars().collect();
    let mut u16_at = Vec::with_capacity(chars.len() + 1);
    let mut acc = 0i32;
    for c in &chars {
        u16_at.push(acc);
        acc += c.len_utf16() as i32;
    }
    u16_at.push(acc);
    let mut out = Vec::new();
    let mut i = 0;
    let n = chars.len();
    while i < n {
        if chars[i].is_whitespace() {
            i += 1;
            continue;
        }
        let start = i;
        if sentences {
            while i < n && !".!?…".contains(chars[i]) {
                i += 1;
            }
            while i < n && ".!?…".contains(chars[i]) {
                i += 1;
            }
            while i < n && "\"'”’)]".contains(chars[i]) {
                i += 1;
            }
        } else {
            while i < n && !chars[i].is_whitespace() {
                i += 1;
            }
        }
        let mut end = i;
        while end > start && chars[end - 1].is_whitespace() {
            end -= 1;
        }
        if end > start {
            out.push((u16_at[start], u16_at[end]));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn words_and_sentences() {
        let t = "He left. “Why?” she asked… Then  done";
        assert_eq!(units(t, false).len(), 7);
        let s: Vec<String> = units(t, true)
            .iter()
            .map(|&(a, b)| {
                String::from_utf16(&t.encode_utf16().collect::<Vec<_>>()[a as usize..b as usize])
                    .unwrap()
            })
            .collect();
        assert_eq!(s, vec!["He left.", "“Why?”", "she asked…", "Then  done"]);
    }
}
