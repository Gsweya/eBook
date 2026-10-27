//! Where a reader stands against a reading plan today, as in the Android app's `Plans.kt`.

use crate::dates;
use crate::model::{Book, BookFormat, ReadingPlan};

/// Fractions are of the whole book (0..1).
pub struct PlanStatus {
    pub days_left: i64,
    /// Share of the book to read each remaining day, today included.
    pub per_day: f32,
    /// How far along the reader should be by today to stay on schedule.
    pub expected: f32,
    pub done: bool,
    pub overdue: bool,
}

impl PlanStatus {
    pub fn on_track(&self, progress: f32) -> bool {
        self.done || progress >= self.expected - 0.02
    }

    /// "7 days left", "1 day left", "due today" or "overdue".
    pub fn due_label(&self) -> String {
        match self.days_left - 1 {
            n if n < 0 => "overdue".into(),
            0 => "due today".into(),
            1 => "1 day left".into(),
            n => format!("{n} days left"),
        }
    }
}

pub fn status(plan: &ReadingPlan, book: &Book, today: i64) -> PlanStatus {
    let target = dates::parse(&plan.target_date).unwrap_or(today);
    let start = dates::parse(&plan.start_date).unwrap_or(today);
    let done = book.progress >= 0.995;
    let days_left = target - today + 1;
    let total = (target - start + 1).max(1);
    let elapsed = (today - start).clamp(0, total);
    let expected =
        plan.start_progress + (1.0 - plan.start_progress) * elapsed as f32 / total as f32;
    let per_day = if done {
        0.0
    } else {
        (1.0 - book.progress) / days_left.max(1) as f32
    };
    PlanStatus {
        days_left,
        per_day,
        expected,
        done,
        overdue: !done && days_left <= 0,
    }
}

/// "2 chapters a day", "12 pages a day" or "8% a day".
pub fn pace_text(book: &Book, per_day: f32) -> String {
    let unit =
        |n: i32, one: &str, many: &str| format!("{n} {} a day", if n == 1 { one } else { many });
    if book.format == BookFormat::PDF && book.page_count > 0 {
        unit(
            ((book.page_count as f32 * per_day).ceil() as i32).max(1),
            "page",
            "pages",
        )
    } else if book.chapter_count > 1 {
        unit(
            ((book.chapter_count as f32 * per_day).ceil() as i32).max(1),
            "chapter",
            "chapters",
        )
    } else {
        format!("{}% a day", ((per_day * 100.0).ceil() as i32).max(1))
    }
}

/// "25 Nov" for a "yyyy-MM-dd" date.
pub fn short_date(key: &str) -> String {
    match dates::parse(key) {
        Some(d) => {
            let (_, m, day) = dates::civil_from_days(d);
            format!("{day} {}", dates::short_month(m))
        }
        None => key.to_string(),
    }
}
