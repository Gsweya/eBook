//! How long a book took to read, or has taken so far: a port of the Android app's `ReadThrough.kt`.

use std::collections::BTreeMap;

use crate::dates;
use crate::model::Book;

/// Progress at which a book counts as finished.
pub const FINISHED: f32 = 0.995;

/// A book's reading from its first reading day to the day it was finished (or today, while unfinished).
/// Days are day numbers (see [`dates`]).
#[derive(Debug, Clone, PartialEq)]
pub struct ReadThrough {
    pub start: i64,
    /// The day the book was finished, or None while it is still being read.
    pub end: Option<i64>,
    /// Calendar days from `start` to `end` (or today), both counted.
    pub days: i64,
    /// Days with any reading time in this book.
    pub reading_days: usize,
    pub reading_ms: i64,
}

impl ReadThrough {
    pub fn finished(&self) -> bool {
        self.end.is_some()
    }

    /// "Finished in 12 days", "Finished in a day", or "Reading for 5 days".
    pub fn headline(&self) -> String {
        match (self.finished(), self.days <= 1) {
            (true, true) => "Finished in a day".into(),
            (true, false) => format!("Finished in {} days", self.days),
            (false, true) => "Started today".into(),
            (false, false) => format!("Reading for {} days", self.days),
        }
    }

    /// "3 Oct – 14 Oct", or one date when it all happened on one day; with the year when it isn't this year.
    pub fn date_range(&self, today: i64) -> String {
        let this_year = dates::civil_from_days(today).0;
        let fmt = |d: i64| {
            let (y, m, day) = dates::civil_from_days(d);
            if y == this_year {
                format!("{day} {}", dates::short_month(m))
            } else {
                format!("{day} {} {y}", dates::short_month(m))
            }
        };
        let last = self.end.unwrap_or(today);
        if self.start == last {
            fmt(self.start)
        } else {
            let to = if self.end.is_none() {
                "today".to_string()
            } else {
                fmt(last)
            };
            format!("{} – {to}", fmt(self.start))
        }
    }
}

/// Works out `book`'s read-through from its own start and finish times, falling back to the per-day reading
/// log (`book_days`) for books read before those were kept. `day_of` turns a time in ms into a local day
/// number. None for a book that hasn't been read yet.
pub fn read_through(
    book: &Book,
    book_days: &BTreeMap<String, BTreeMap<String, i64>>,
    today: i64,
    day_of: impl Fn(i64) -> i64,
) -> Option<ReadThrough> {
    let mut read: Vec<i64> = book_days
        .iter()
        .filter(|(_, books)| books.get(&book.id).copied().unwrap_or(0) > 0)
        .filter_map(|(day, _)| dates::parse(day))
        .collect();
    read.sort_unstable();
    let started = (book.started_at > 0).then(|| day_of(book.started_at));
    let start = [started, read.first().copied()]
        .into_iter()
        .flatten()
        .min()?;
    let end = if book.finished_at > 0 {
        Some(day_of(book.finished_at))
    } else if book.progress >= FINISHED {
        // Finished before finish times were kept: the last day it was read.
        Some(
            read.last()
                .copied()
                .or_else(|| (book.position_at > 0).then(|| day_of(book.position_at)))
                .unwrap_or(start),
        )
    } else {
        None
    }
    .map(|e| e.max(start));
    let days = (end.unwrap_or(today) - start + 1).max(1);
    Some(ReadThrough {
        start,
        end,
        days,
        reading_days: read.len(),
        reading_ms: book.reading_ms,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(k: &str) -> i64 {
        dates::parse(k).unwrap()
    }

    #[test]
    fn from_the_log_and_from_times() {
        let mut log: BTreeMap<String, BTreeMap<String, i64>> = BTreeMap::new();
        for k in ["2026-10-03", "2026-10-05", "2026-10-14"] {
            log.entry(k.into()).or_default().insert("b".into(), 60_000);
        }
        let mut b = Book {
            id: "b".into(),
            ..Book::default()
        };
        let today = day("2026-11-25");
        // Unfinished: from the first day read until today.
        let r = read_through(&b, &log, today, |_| 0).unwrap();
        assert_eq!(
            (r.start, r.end, r.reading_days),
            (day("2026-10-03"), None, 3)
        );
        assert_eq!(r.headline(), "Reading for 54 days");
        assert_eq!(r.date_range(today), "3 Oct – today");
        // Finished before finish times were kept: ends on the last day read.
        b.progress = 1.0;
        let r = read_through(&b, &log, today, |_| 0).unwrap();
        assert_eq!(r.headline(), "Finished in 12 days");
        assert_eq!(r.date_range(today), "3 Oct – 14 Oct");
        // A finish time wins; a start time earlier than the log moves the start.
        b.started_at = 1;
        b.finished_at = 2;
        let r = read_through(&b, &log, today, |ms| {
            if ms == 1 {
                day("2025-12-30")
            } else {
                day("2026-01-02")
            }
        })
        .unwrap();
        assert_eq!(r.date_range(today), "30 Dec 2025 – 2 Jan");
        assert_eq!(r.days, 4);
        // Never read: nothing to show.
        let fresh = Book {
            id: "x".into(),
            ..Book::default()
        };
        assert!(read_through(&fresh, &log, today, |_| 0).is_none());
    }
}
