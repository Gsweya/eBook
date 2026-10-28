//! Sync through the user's Google Drive: the merge at its heart, on plain JSON so fields this version doesn't
//! know survive. The format and every rule are in `shared/sync/README.md`; the Android app implements the same
//! rules in `Sync.kt`, and both run the cases in `shared/sync/cases/`.

use std::cmp::Ordering;
use std::collections::{BTreeMap, HashSet};
use std::io::{Read, Write};

use serde_json::{Map, Value, json};

pub const FORMAT: i64 = 1;
/// Name of the sync document in the Drive app data folder.
pub const DOC_NAME: &str = "gbook-sync.json.gz";

type Obj = Map<String, Value>;

/// Library lists synced as entity maps, with the field that keys them.
const LISTS: [(&str, &str); 6] = [
    ("books", "id"),
    ("highlights", "id"),
    ("posts", "id"),
    ("collections", "id"),
    ("reminders", "id"),
    ("plans", "bookId"),
];
const BOOK_LOCAL: [&str; 7] = [
    "hasCover",
    "coverColor",
    "chapterCount",
    "pageCount",
    "textReady",
    "textVersion",
    "backedUpAt",
];
/// Book fields that come from the counters, with the counter that holds them.
const BOOK_COUNTERS: [&str; 2] = ["readingMs", "pagesRead"];
const SETTINGS_LOCAL: [&str; 3] = ["fontSize", "margin", "avatarVersion"];
const SELF_STAMPED: [&str; 4] = ["position", "progress", "positionAt", "lastOpenedAt"];

/// `[ms, device]`, compared by ms and then device.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct Stamp(i64, String);

impl Stamp {
    fn of(v: &Value) -> Option<Stamp> {
        let a = v.as_array()?;
        Some(Stamp(num(a.first()?), a.get(1)?.as_str()?.to_string()))
    }

    fn field(&self, v: &Value) -> Value {
        json!([self.0, self.1, v])
    }

    fn json(&self) -> Value {
        json!([self.0, self.1])
    }
}

fn num(v: &Value) -> i64 {
    v.as_i64()
        .or_else(|| v.as_f64().map(|f| f as i64))
        .unwrap_or(0)
}

pub fn empty_doc() -> Value {
    json!({ "format": FORMAT })
}

/// gzip-compressed JSON, as stored on Drive.
pub fn encode(doc: &Value) -> Vec<u8> {
    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    let _ = gz.write_all(&serde_json::to_vec(doc).unwrap_or_default());
    gz.finish().unwrap_or_default()
}

pub fn decode(bytes: &[u8]) -> Result<Value, String> {
    let mut s = Vec::new();
    flate2::read::GzDecoder::new(bytes)
        .read_to_end(&mut s)
        .map_err(|e| format!("Unreadable sync file: {e}"))?;
    let v: Value = serde_json::from_slice(&s).map_err(|e| format!("Unreadable sync file: {e}"))?;
    if !v.is_object() {
        return Err("Unreadable sync file".into());
    }
    if num(&v["format"]) > FORMAT {
        return Err("The sync file is from a newer GBook: update this app to sync".into());
    }
    Ok(v)
}

fn obj_mut<'a>(parent: &'a mut Value, key: &str) -> &'a mut Obj {
    let p = parent.as_object_mut().expect("object");
    let v = p.entry(key).or_insert_with(|| json!({}));
    if !v.is_object() {
        *v = json!({});
    }
    v.as_object_mut().unwrap()
}

fn entity_alive(e: &Value) -> bool {
    let Some(fields) = e.get("f").and_then(Value::as_object) else {
        return false;
    };
    match e.get("del").and_then(Stamp::of) {
        None => !fields.is_empty(),
        Some(del) => fields.values().filter_map(Stamp::of).any(|s| s > del),
    }
}

/// Sets field `f` of entity `e` to `v` unless it already holds a value with a stamp at least as new.
fn set_field(e: &mut Value, f: &str, st: &Stamp, v: &Value) {
    let fields = obj_mut(e, "f");
    if fields
        .get(f)
        .and_then(Stamp::of)
        .is_some_and(|cur| cur >= *st)
    {
        return;
    }
    fields.insert(f.to_string(), st.field(v));
}

// ---- Join ----

/// Folds `other` into `doc`: the newer stamp wins per field, the newer deletion, and per device and key the
/// larger counter.
pub fn join(doc: &mut Value, other: &Value) {
    if !doc.is_object() {
        *doc = empty_doc();
    }
    let Some(o) = other.as_object() else {
        return;
    };
    let fmt = num(&doc["format"]).max(num(&other["format"])).max(FORMAT);
    doc["format"] = json!(fmt);
    for (kind, _) in LISTS {
        let Some(theirs) = o.get(kind).and_then(Value::as_object) else {
            continue;
        };
        let mine = obj_mut(doc, kind);
        for (id, e) in theirs {
            join_entity(mine.entry(id.clone()).or_insert_with(|| json!({})), e);
        }
    }
    if let Some(s) = o.get("settings") {
        let d = doc.as_object_mut().unwrap();
        join_entity(d.entry("settings").or_insert_with(|| json!({})), s);
    }
    if let Some(theirs) = o.get("counters").and_then(Value::as_object) {
        let mine = obj_mut(doc, "counters");
        for (dev, c) in theirs {
            join_max(mine.entry(dev.clone()).or_insert_with(|| json!({})), c);
        }
    }
}

fn join_entity(mine: &mut Value, theirs: &Value) {
    if !mine.is_object() {
        *mine = json!({});
    }
    if let Some(tf) = theirs.get("f").and_then(Value::as_object) {
        for (f, v) in tf {
            let Some(st) = Stamp::of(v) else { continue };
            let fields = obj_mut(mine, "f");
            if fields.get(f).and_then(Stamp::of).is_none_or(|cur| st > cur) {
                fields.insert(f.clone(), v.clone());
            }
        }
    }
    if let Some(td) = theirs.get("del").and_then(Stamp::of)
        && mine
            .get("del")
            .and_then(Stamp::of)
            .is_none_or(|cur| td > cur)
    {
        mine["del"] = td.json();
    }
}

/// Nested maps of numbers, keeping the larger number at each key.
fn join_max(mine: &mut Value, theirs: &Value) {
    let Some(t) = theirs.as_object() else { return };
    if !mine.is_object() {
        *mine = json!({});
    }
    let m = mine.as_object_mut().unwrap();
    for (k, v) in t {
        if v.is_object() {
            join_max(m.entry(k.clone()).or_insert_with(|| json!({})), v);
        } else if m.get(k).is_none_or(|cur| num(v) > num(cur)) {
            m.insert(k.clone(), json!(num(v)));
        }
    }
}

// ---- Record ----

fn is_synced(kind: &str, f: &str) -> bool {
    match kind {
        "books" => !BOOK_LOCAL.contains(&f) && !BOOK_COUNTERS.contains(&f),
        "settings" => !SETTINGS_LOCAL.contains(&f),
        _ => true,
    }
}

/// The time a self-stamped book field really changed, or None for ordinary fields.
fn own_ms(kind: &str, item: &Obj, f: &str) -> Option<i64> {
    if kind != "books" || !SELF_STAMPED.contains(&f) {
        return None;
    }
    let opened = item.get("lastOpenedAt").map(num).unwrap_or(0);
    if f == "lastOpenedAt" {
        return Some(opened);
    }
    let at = item.get("positionAt").map(num).unwrap_or(0);
    Some(if at > 0 { at } else { opened })
}

fn write_all(e: &mut Value, kind: &str, l: &Obj, s: &Stamp) {
    for (f, v) in l {
        if !is_synced(kind, f) {
            continue;
        }
        let st = match own_ms(kind, l, f) {
            Some(ms) if ms > 0 => Stamp(ms, s.1.clone()),
            _ => s.clone(),
        };
        set_field(e, f, &st, v);
    }
}

/// Text the reader writes, which is merged rather than replaced when two devices both changed it.
const TEXT_FIELDS: [(&str, &str); 1] = [("highlights", "note")];

fn is_empty(v: &Value) -> bool {
    match v {
        Value::Null => true,
        Value::Bool(b) => !b,
        Value::Number(n) => n.as_f64() == Some(0.0),
        Value::String(s) => s.is_empty(),
        Value::Array(a) => a.is_empty(),
        Value::Object(o) => o.is_empty(),
    }
}

/// Both versions of a text, unless one already contains the other.
fn combine_text(theirs: &Value, mine: &Value) -> Value {
    let (a, b) = (theirs.as_str().unwrap_or(""), mine.as_str().unwrap_or(""));
    if a.is_empty() || b.contains(a) {
        mine.clone()
    } else if b.is_empty() || a.contains(b) {
        theirs.clone()
    } else {
        Value::String(format!("{a}\n\n{b}"))
    }
}

/// Writes the fields of `l` that changed since `b`. A note changed on another device meanwhile keeps both texts.
fn record_changes(e: &mut Value, kind: &str, l: &Obj, b: &Obj, s: &Stamp) {
    for (f, v) in l {
        if !is_synced(kind, f) || own_ms(kind, l, f).is_some() || b.get(f) == Some(v) {
            continue;
        }
        let theirs = e["f"].get(f).and_then(|x| x.get(2)).cloned();
        let v = match theirs {
            Some(t) if TEXT_FIELDS.contains(&(kind, f.as_str())) && b.get(f) != Some(&t) => {
                combine_text(&t, v)
            }
            _ => v.clone(),
        };
        set_field(e, f, s, &v);
    }
}

/// An item the account already has, met for the first time here: the account's values stay, and this
/// device's only fill fields the account has left empty, so a first sync never overwrites anything.
fn fill_gaps(e: &mut Value, kind: &str, l: &Obj, s: &Stamp) {
    for (f, v) in l {
        if !is_synced(kind, f) || own_ms(kind, l, f).is_some() || is_empty(v) {
            continue;
        }
        if e["f"].get(f).and_then(|x| x.get(2)).is_none_or(is_empty) {
            set_field(e, f, s, v);
        }
    }
}

fn changed(kind: &str, l: &Obj, b: &Obj) -> bool {
    l.iter()
        .any(|(f, v)| is_synced(kind, f) && b.get(f) != Some(v))
}

fn index<'a>(list: Option<&'a Value>, key: &str) -> BTreeMap<String, &'a Obj> {
    let mut m = BTreeMap::new();
    for it in list.and_then(Value::as_array).into_iter().flatten() {
        if let Some(o) = it.as_object()
            && let Some(id) = o.get(key).and_then(Value::as_str)
        {
            m.insert(id.to_string(), o);
        }
    }
    m
}

/// Writes what changed in `local` since `base` into `doc`, as `device` at `now`. `base` is None before a
/// device's first sync.
pub fn record(doc: &mut Value, base: Option<&Value>, local: &Value, device: &str, now: i64) {
    if !doc.is_object() {
        *doc = empty_doc();
    }
    let s = Stamp(now, device.to_string());
    for (kind, key) in LISTS {
        let l_items = index(local.get(kind), key);
        let b_items = index(base.and_then(|b| b.get(kind)), key);
        let entities = obj_mut(doc, kind);
        for (id, l) in &l_items {
            let b = b_items.get(id);
            let e = entities.entry(id.clone()).or_insert_with(|| json!({}));
            let missing = e
                .get("f")
                .and_then(Value::as_object)
                .is_none_or(Obj::is_empty)
                && e.get("del").is_none();
            if missing {
                write_all(e, kind, l, &s);
            } else if entity_alive(e) {
                match b {
                    Some(b) => record_changes(e, kind, l, b, &s),
                    None => fill_gaps(e, kind, l, &s),
                }
            } else if match b {
                Some(b) => changed(kind, l, b),
                None => base.is_some(),
            } {
                write_all(e, kind, l, &s);
            }
            if kind == "books" && entity_alive(e) {
                for f in SELF_STAMPED {
                    if let (Some(v), Some(ms)) = (l.get(f), own_ms(kind, l, f))
                        && ms > 0
                    {
                        set_field(e, f, &Stamp(ms, device.to_string()), v);
                    }
                }
            }
        }
        for id in b_items.keys() {
            if l_items.contains_key(id) {
                continue;
            }
            if let Some(e) = entities.get_mut(id)
                && entity_alive(e)
            {
                let newest = e["f"]
                    .as_object()
                    .into_iter()
                    .flat_map(|f| f.values())
                    .filter_map(Stamp::of)
                    .map(|st| st.0)
                    .max()
                    .unwrap_or(0);
                e["del"] = Stamp(now.max(newest + 1), device.to_string()).json();
            }
        }
    }

    if let Some(l) = local.get("settings").and_then(Value::as_object) {
        let d = doc.as_object_mut().unwrap();
        let e = d.entry("settings").or_insert_with(|| json!({}));
        let empty = e
            .get("f")
            .and_then(Value::as_object)
            .is_none_or(Obj::is_empty);
        if empty {
            write_all(e, "settings", l, &s);
        } else {
            match base
                .and_then(|b| b.get("settings"))
                .and_then(Value::as_object)
            {
                Some(b) => record_changes(e, "settings", l, b, &s),
                None => fill_gaps(e, "settings", l, &s),
            }
        }
    }

    record_counters(doc, base, local, device);
}

/// Totals over all devices, as nested maps.
fn totals(doc: &Value) -> Value {
    let mut t = json!({});
    for c in doc
        .get("counters")
        .and_then(Value::as_object)
        .into_iter()
        .flat_map(|m| m.values())
    {
        add_into(&mut t, c);
    }
    t
}

fn add_into(acc: &mut Value, v: &Value) {
    let Some(src) = v.as_object() else { return };
    if !acc.is_object() {
        *acc = json!({});
    }
    let a = acc.as_object_mut().unwrap();
    for (k, x) in src {
        if x.is_object() {
            add_into(a.entry(k.clone()).or_insert_with(|| json!({})), x);
        } else {
            let cur = a.get(k).map(num).unwrap_or(0);
            a.insert(k.clone(), json!(cur + num(x)));
        }
    }
}

fn record_counters(doc: &mut Value, base: Option<&Value>, local: &Value, device: &str) {
    obj_mut(doc, "counters");
    let total = totals(doc);
    // (counter path, local value, value before) for every key.
    let mut deltas: Vec<(Vec<String>, i64)> = Vec::new();
    let mut add = |path: Vec<String>, lv: i64, before: i64| {
        if lv - before > 0 {
            deltas.push((path, lv - before));
        }
    };
    let lookup = |root: Option<&Value>, path: &[&str]| -> i64 {
        let mut v = root;
        for p in path {
            v = v.and_then(|x| x.get(*p));
        }
        v.map(num).unwrap_or(0)
    };
    for kind in ["readingLog", "pagesLog"] {
        for (day, lv) in local
            .get(kind)
            .and_then(Value::as_object)
            .into_iter()
            .flatten()
        {
            let before = match base {
                Some(b) => lookup(Some(b), &[kind, day]),
                None => lookup(Some(&total), &[kind, day]),
            };
            add(vec![kind.into(), day.clone()], num(lv), before);
        }
    }
    for (day, books) in local
        .get("bookDays")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
    {
        for (id, lv) in books.as_object().into_iter().flatten() {
            let before = match base {
                Some(b) => lookup(Some(b), &["bookDays", day, id]),
                None => lookup(Some(&total), &["bookDays", day, id]),
            };
            add(
                vec!["bookDays".into(), day.clone(), id.clone()],
                num(lv),
                before,
            );
        }
    }
    let base_books = index(base.and_then(|b| b.get("books")), "id");
    for (id, book) in index(local.get("books"), "id") {
        for f in BOOK_COUNTERS {
            let lv = book.get(f).map(num).unwrap_or(0);
            let before = match base {
                Some(_) => base_books
                    .get(&id)
                    .and_then(|b| b.get(f))
                    .map(num)
                    .unwrap_or(0),
                None => lookup(Some(&total), &[f, &id]),
            };
            add(vec![f.into(), id.clone()], lv, before);
        }
    }
    if deltas.is_empty() {
        return;
    }
    let mine = obj_mut(doc, "counters")
        .entry(device.to_string())
        .or_insert_with(|| json!({}));
    for (path, d) in deltas {
        add_at(mine, &path, d);
    }
}

/// Adds `d` to the number at `path` in nested maps, creating them as needed.
fn add_at(root: &mut Value, path: &[String], d: i64) {
    let Some((last, dirs)) = path.split_last() else {
        return;
    };
    let mut at = root;
    for p in dirs {
        if !at.is_object() {
            *at = json!({});
        }
        at = at
            .as_object_mut()
            .unwrap()
            .entry(p.clone())
            .or_insert_with(|| json!({}));
    }
    if !at.is_object() {
        *at = json!({});
    }
    let o = at.as_object_mut().unwrap();
    let cur = o.get(last).map(num).unwrap_or(0);
    o.insert(last.clone(), json!(cur + d));
}

// ---- Project ----

fn sort_key(kind: &str, o: &Obj) -> (i64, String) {
    let at = if kind == "books" {
        "addedAt"
    } else {
        "createdAt"
    };
    (
        o.get(at).map(num).unwrap_or(0),
        o.get(if kind == "plans" { "bookId" } else { "id" })
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string(),
    )
}

fn values(e: &Value) -> Obj {
    let mut o = Obj::new();
    for (f, v) in e.get("f").and_then(Value::as_object).into_iter().flatten() {
        if let Some(x) = v.as_array().and_then(|a| a.get(2)) {
            o.insert(f.clone(), x.clone());
        }
    }
    o
}

/// The library as `doc` describes it, keeping `local`'s per-device fields. Books whose file isn't on this
/// device yet are left out, with the highlights, posts and plans that belong to them.
pub fn project(doc: &Value, local: &Value) -> Value {
    let mut out = local.clone();
    if !out.is_object() {
        out = json!({});
    }
    let total = totals(doc);
    let empty = Obj::new();
    let mut book_ids: HashSet<String> = HashSet::new();
    for (kind, key) in LISTS {
        let entities = doc.get(kind).and_then(Value::as_object).unwrap_or(&empty);
        let local_list: Vec<&Obj> = local
            .get(kind)
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(Value::as_object)
            .collect();
        let keep = |o: &Obj| -> bool {
            match kind {
                "highlights" | "posts" | "plans" => o
                    .get("bookId")
                    .and_then(Value::as_str)
                    .is_some_and(|b| book_ids.contains(b)),
                "reminders" => match o.get("bookId").and_then(Value::as_str) {
                    None => true,
                    Some(b) => book_ids.contains(b),
                },
                _ => true,
            }
        };
        let mut here: HashSet<&str> = HashSet::new();
        let mut result: Vec<Value> = Vec::new();
        for l in &local_list {
            let Some(id) = l.get(key).and_then(Value::as_str) else {
                continue;
            };
            here.insert(id);
            let Some(e) = entities.get(id).filter(|e| entity_alive(e)) else {
                continue;
            };
            let mut o = (*l).clone();
            for (f, v) in values(e) {
                if is_synced(kind, &f) {
                    o.insert(f, v);
                }
            }
            if kind == "books" {
                for f in BOOK_COUNTERS {
                    let t = total.get(f).and_then(|m| m.get(id)).map(num).unwrap_or(0);
                    o.insert(f.to_string(), json!(t));
                }
            }
            if keep(&o) {
                result.push(Value::Object(o));
            }
        }
        if kind != "books" {
            let mut new: Vec<Obj> = entities
                .iter()
                .filter(|(id, e)| !here.contains(id.as_str()) && entity_alive(e))
                .map(|(id, e)| {
                    let mut o = values(e);
                    o.insert(key.to_string(), json!(id));
                    o
                })
                .filter(|o| keep(o))
                .collect();
            new.sort_by_key(|o| sort_key(kind, o));
            if kind == "posts" {
                new.sort_by(|a, b| match sort_key(kind, b).0.cmp(&sort_key(kind, a).0) {
                    Ordering::Equal => sort_key(kind, a).1.cmp(&sort_key(kind, b).1),
                    o => o,
                });
                let mut v: Vec<Value> = new.into_iter().map(Value::Object).collect();
                v.append(&mut result);
                result = v;
            } else {
                result.extend(new.into_iter().map(Value::Object));
            }
        }
        if kind == "books" {
            book_ids = result
                .iter()
                .filter_map(|b| b.get("id").and_then(Value::as_str))
                .map(String::from)
                .collect();
        }
        out[kind] = Value::Array(result);
    }

    let mut settings = local
        .get("settings")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    if let Some(e) = doc.get("settings") {
        for (f, v) in values(e) {
            if is_synced("settings", &f) {
                settings.insert(f, v);
            }
        }
    }
    out["settings"] = Value::Object(settings);
    for kind in ["readingLog", "pagesLog", "bookDays"] {
        out[kind] = total.get(kind).cloned().unwrap_or_else(|| json!({}));
    }
    out
}

/// One sync's merge: joins `remote` (when it was pulled) into `doc`, records the library's changes since
/// `base`, and returns the library to apply. The caller saves `doc`, and as the next base the library as it
/// encodes once applied.
pub fn merge(
    doc: &mut Value,
    remote: Option<&Value>,
    base: Option<&Value>,
    local: &Value,
    device: &str,
    now: i64,
) -> Value {
    if let Some(r) = remote {
        join(doc, r);
    }
    record(doc, base, local, device, now);
    project(doc, local)
}

/// Books the account has that this device doesn't yet: (id, file name).
pub fn pending_books(doc: &Value, local: &Value) -> Vec<(String, String)> {
    let here: HashSet<&str> = local
        .get("books")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|b| b.get("id").and_then(Value::as_str))
        .collect();
    doc.get("books")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
        .filter(|(id, e)| !here.contains(id.as_str()) && entity_alive(e))
        .map(|(id, e)| {
            let name = values(e)
                .get("fileName")
                .and_then(Value::as_str)
                .unwrap_or("book.epub")
                .to_string();
            (id.clone(), name)
        })
        .collect()
}

/// Ids of books deleted on some device.
pub fn deleted_books(doc: &Value) -> HashSet<String> {
    doc.get("books")
        .and_then(Value::as_object)
        .into_iter()
        .flatten()
        .filter(|(_, e)| !entity_alive(e))
        .map(|(id, _)| id.clone())
        .collect()
}

/// Drive name for a book's file: `book-<id>.epub` or `.pdf`.
pub fn book_file_name(id: &str, file_name: &str) -> String {
    let ext = file_name.rsplit_once('.').map(|(_, e)| e).unwrap_or("epub");
    format!("book-{id}.{ext}")
}

/// The book id in a Drive file name from `book_file_name`.
pub fn book_id_of_file(name: &str) -> Option<&str> {
    let rest = name.strip_prefix("book-")?;
    Some(rest.split_once('.').map(|(id, _)| id).unwrap_or(rest))
}

/// 8 random hex digits naming this device in stamps and counters.
pub fn new_device_id() -> String {
    crate::library::new_id()[4..].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn cases_dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../shared/sync/cases")
    }

    /// Runs every case in `shared/sync/cases`. `SYNC_BLESS=1` writes the results into the cases instead.
    #[test]
    fn shared_cases() {
        let bless = std::env::var_os("SYNC_BLESS").is_some();
        let mut n = 0;
        for entry in std::fs::read_dir(cases_dir()).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().is_none_or(|e| e != "json") {
                continue;
            }
            let mut case: Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
            let mut doc = case["doc"].clone();
            if doc.is_null() {
                doc = empty_doc();
            }
            let remote = Some(&case["remote"]).filter(|v| !v.is_null());
            let base = Some(&case["base"]).filter(|v| !v.is_null());
            let library = merge(
                &mut doc,
                remote,
                base,
                &case["local"],
                case["device"].as_str().unwrap(),
                num(&case["now"]),
            );
            let pending: Vec<Value> = pending_books(&doc, &library)
                .into_iter()
                .map(|(id, f)| json!([id, f]))
                .collect();
            if let Some(want) = case.get("expect_pending") {
                assert_eq!(
                    &Value::Array(pending),
                    want,
                    "{}: pending books",
                    path.display()
                );
            }
            if bless {
                case["expect"] = json!({ "doc": doc, "library": library });
                std::fs::write(&path, serde_json::to_string_pretty(&case).unwrap() + "\n").unwrap();
            } else {
                assert_eq!(doc, case["expect"]["doc"], "{}: doc", path.display());
                assert_eq!(
                    library,
                    case["expect"]["library"],
                    "{}: library",
                    path.display()
                );
                // Syncing again with nothing new changes nothing.
                let mut again = doc.clone();
                let lib2 = merge(
                    &mut again,
                    Some(&doc),
                    Some(&library),
                    &library,
                    "aaaa1111",
                    5000,
                );
                assert_eq!(
                    again,
                    doc,
                    "{}: second sync changed the doc",
                    path.display()
                );
                assert_eq!(
                    lib2,
                    library,
                    "{}: second sync changed the library",
                    path.display()
                );
            }
            n += 1;
        }
        assert!(n > 0);
    }

    #[test]
    fn encode_round_trips() {
        let d = json!({"format": 1, "books": {"a": {"f": {"title": [1, "d", "T"]}}}});
        assert_eq!(decode(&encode(&d)).unwrap(), d);
        assert!(decode(&encode(&json!({"format": 99}))).is_err());
    }

    #[test]
    fn join_is_order_independent() {
        let a = json!({"format": 1, "books": {"x": {"f": {"t": [5, "a", 1], "u": [1, "a", 1]}}},
            "counters": {"a": {"readingLog": {"d": 10}}}});
        let b = json!({"format": 1, "books": {"x": {"f": {"t": [3, "b", 2], "u": [2, "b", 2]}, "del": [4, "b"]}},
            "counters": {"a": {"readingLog": {"d": 7}}, "b": {"readingLog": {"d": 3}}}});
        let mut ab = a.clone();
        join(&mut ab, &b);
        let mut ba = b.clone();
        join(&mut ba, &a);
        assert_eq!(ab, ba);
        assert!(entity_alive(&ab["books"]["x"]));
        assert_eq!(ab["counters"]["a"]["readingLog"]["d"], 10);
    }
}
