//! The reader's top-right badge: your profile picture inside a ring that fills towards today's reading goal,
//! with today's reading time beside it. Clicking it shows today's reading and this book's progress and plan.

use std::rc::Rc;

use gbook_core::library::{format_duration, reading_streak};
use gbook_core::{dates, plans};
use gtk::pango;
use gtk::prelude::*;

use crate::state::{AppRc, today};
use crate::widgets::*;

/// A ring `size` px wide showing `fraction` of the way round, in the accent, over a faint track.
fn ring(
    app: &AppRc,
    size: i32,
    width: f64,
    fraction: impl Fn() -> f64 + 'static,
) -> gtk::DrawingArea {
    let da = gtk::DrawingArea::new();
    da.set_content_width(size);
    da.set_content_height(size);
    da.set_can_target(false);
    let a = app.clone();
    da.set_draw_func(move |_, cr, w, h| {
        let c = a.colors();
        let (cx, cy) = (w as f64 / 2.0, h as f64 / 2.0);
        let r = (w.min(h) as f64 - width) / 2.0;
        let rgb =
            |v: crate::theme::Rgb| (v.0 as f64 / 255.0, v.1 as f64 / 255.0, v.2 as f64 / 255.0);
        let (tr, tg, tb) = rgb(c.text);
        cr.set_line_width(width);
        cr.set_source_rgba(tr, tg, tb, 0.12);
        cr.arc(cx, cy, r, 0.0, std::f64::consts::TAU);
        let _ = cr.stroke();
        let f = fraction().clamp(0.0, 1.0);
        if f > 0.0 {
            let (ar, ag, ab) = rgb(c.accent);
            cr.set_source_rgb(ar, ag, ab);
            cr.set_line_cap(gtk::cairo::LineCap::Round);
            let start = -std::f64::consts::FRAC_PI_2;
            cr.arc(cx, cy, r, start, start + f * std::f64::consts::TAU);
            let _ = cr.stroke();
        }
    });
    da
}

/// Today's reading so far: the saved log plus `pending` ms not yet saved.
fn today_ms(app: &AppRc, pending: i64) -> i64 {
    let (_, key) = today();
    app.data().reading_log.get(&key).copied().unwrap_or(0) + pending
}

fn goal_fraction(app: &AppRc, pending: i64) -> f64 {
    let goal = app.data().settings.daily_goal_min as i64 * 60_000;
    if goal <= 0 {
        0.0
    } else {
        today_ms(app, pending) as f64 / goal as f64
    }
}

pub struct Badge {
    pub button: gtk::Button,
    ring: gtk::DrawingArea,
    time: gtk::Label,
    avatar_slot: gtk::Overlay,
    pending: Rc<std::cell::Cell<i64>>,
}

impl Badge {
    pub fn new(app: &AppRc, book_id: &str) -> Rc<Badge> {
        let pending = Rc::new(std::cell::Cell::new(0i64));
        let button = gtk::Button::new();
        button.add_css_class("reading-badge");
        button.set_tooltip_text(Some("Today's reading"));
        let row = hbox(8);
        let slot = gtk::Overlay::new();
        let (a, p) = (app.clone(), pending.clone());
        let ring = ring(app, 40, 3.0, move || goal_fraction(&a, p.get()));
        slot.set_child(Some(&ring));
        row.append(&slot);
        let time = label("", &["semibold", "caption"]);
        time.set_valign(gtk::Align::Center);
        row.append(&time);
        button.set_child(Some(&row));
        let b = Rc::new(Badge {
            button,
            ring,
            time,
            avatar_slot: slot,
            pending,
        });
        b.refresh_avatar(app);
        b.update(app, 0);
        let (a, id, w) = (app.clone(), book_id.to_string(), Rc::downgrade(&b));
        b.button.connect_clicked(move |btn| {
            if let Some(b) = w.upgrade() {
                b.popover(&a, &id, btn);
            }
        });
        b
    }

    /// The profile picture, inside the ring.
    pub fn refresh_avatar(&self, app: &AppRc) {
        while let Some(o) = self.avatar_slot.last_child() {
            if o == self.ring.clone().upcast::<gtk::Widget>() {
                break;
            }
            self.avatar_slot.remove_overlay(&o);
        }
        let av = avatar(app, 30);
        av.set_halign(gtk::Align::Center);
        av.set_valign(gtk::Align::Center);
        self.avatar_slot.add_overlay(&av);
    }

    /// Today's time and the ring, counting `pending` ms of reading not saved yet.
    pub fn update(&self, app: &AppRc, pending: i64) {
        self.pending.set(pending);
        let ms = today_ms(app, pending);
        self.time.set_text(&format_duration(ms));
        self.ring.queue_draw();
    }

    fn popover(self: &Rc<Self>, app: &AppRc, book_id: &str, anchor: &gtk::Button) {
        let col = vbox(14);
        col.set_size_request(320, -1);
        col.set_margin_top(4);
        col.set_margin_bottom(4);
        col.set_margin_start(4);
        col.set_margin_end(4);
        let d = app.data().clone();
        let (today_n, _) = today();
        let pending = self.pending.get();
        let ms = today_ms(app, pending);
        let goal = d.settings.daily_goal_min;

        // Today: a big ring with the time in the middle.
        let head = hbox(16);
        let big = gtk::Overlay::new();
        let (a, p) = (app.clone(), pending);
        big.set_child(Some(&ring(app, 96, 7.0, move || goal_fraction(&a, p))));
        let mid = vbox(0);
        mid.set_halign(gtk::Align::Center);
        mid.set_valign(gtk::Align::Center);
        let t = label(&format_duration(ms), &["serif-title"]);
        t.set_xalign(0.5);
        t.set_attributes(Some(&{
            let l = pango::AttrList::new();
            l.insert(pango::AttrSize::new_size_absolute(19 * pango::SCALE));
            l
        }));
        mid.append(&t);
        let sub = label("today", &["small", "text2"]);
        sub.set_xalign(0.5);
        mid.append(&sub);
        big.add_overlay(&mid);
        head.append(&big);
        let facts = vbox(4);
        facts.set_valign(gtk::Align::Center);
        let goal_line = if goal <= 0 {
            "No daily goal".to_string()
        } else if ms >= goal as i64 * 60_000 {
            let extra = (ms - goal as i64 * 60_000) / 60_000;
            format!(
                "Goal reached 🎉{}",
                if extra > 0 {
                    format!(" +{extra} min")
                } else {
                    String::new()
                }
            )
        } else {
            format!(
                "{} min to go of {goal}",
                ((goal as i64 * 60_000 - ms) / 60_000).max(1)
            )
        };
        facts.append(&label(&goal_line, &["semibold"]));
        let streak = reading_streak(&d.reading_log, today_n);
        facts.append(&label(
            &format!("{} day streak", streak),
            &["caption", "text2"],
        ));
        let week: i64 = (0..7)
            .filter_map(|i| d.reading_log.get(&dates::key(today_n - i)))
            .sum::<i64>()
            + pending;
        facts.append(&label(
            &format!("{} this week", format_duration(week)),
            &["caption", "text2"],
        ));
        head.append(&facts);
        col.append(&head);

        // Change the daily goal in place.
        let chips = hbox(6);
        chips.append(&label("Goal", &["caption", "text2"]));
        for m in [0, 15, 30, 45, 60] {
            let b = gtk::Button::with_label(&if m == 0 {
                "Off".to_string()
            } else {
                format!("{m}")
            });
            b.add_css_class("chip");
            if m == goal {
                b.add_css_class("on");
            }
            let (a, w) = (app.clone(), Rc::downgrade(self));
            b.connect_clicked(move |btn| {
                a.update(|d| d.settings.daily_goal_min = m);
                if let Some(b) = w.upgrade() {
                    b.ring.queue_draw();
                }
                if let Some(p) = btn
                    .ancestor(gtk::Popover::static_type())
                    .and_downcast::<gtk::Popover>()
                {
                    p.popdown();
                }
            });
            chips.append(&b);
        }
        col.append(&chips);

        // This book: progress, time spent and its plan.
        if let Some(book) = d.book(book_id).cloned() {
            let sep = vbox(0);
            sep.add_css_class("row-sep");
            sep.set_margin_start(0);
            col.append(&sep);
            col.append(&label("THIS BOOK", &["eyebrow"]));
            let row = hbox(12);
            row.append(&Cover::new(app, &book, 38));
            let v = vbox(4);
            v.set_hexpand(true);
            let title = label(&book.title, &["semibold"]);
            title.set_ellipsize(pango::EllipsizeMode::End);
            v.append(&title);
            v.append(&label(
                &format!(
                    "{}% read · {} in total",
                    (book.progress * 100.0) as i32,
                    format_duration(book.reading_ms + pending)
                ),
                &["caption", "text2"],
            ));
            v.append(&progress(book.progress.max(0.01), false));
            row.append(&v);
            col.append(&row);
            match d.plans.iter().find(|p| p.book_id == book.id) {
                Some(plan) => {
                    let st = plans::status(plan, &book, today_n);
                    let (tag, class) = if st.done {
                        ("Done 🎉", "accent")
                    } else if st.overdue {
                        ("Overdue", "destructive")
                    } else if st.on_track(book.progress) {
                        ("On track", "ontrack")
                    } else {
                        ("Behind", "behind")
                    };
                    let line = hbox(8);
                    line.append(&label("Plan", &["semibold"]));
                    line.append(&label(tag, &["tag", class]));
                    col.append(&line);
                    if !st.done {
                        col.append(&wrap_label(
                            &format!(
                                "Finish by {} · {} · {}",
                                plans::short_date(&plan.target_date),
                                st.due_label(),
                                plans::pace_text(&book, st.per_day)
                            ),
                            &["caption", "text2"],
                            2,
                        ));
                        // Where you are against where the plan expects you to be today.
                        let expected = label(
                            &format!(
                                "You're at {}%; the plan expects {}% by today",
                                (book.progress * 100.0) as i32,
                                (st.expected * 100.0).round() as i32
                            ),
                            &["caption", "text2"],
                        );
                        col.append(&expected);
                    }
                    let edit = gtk::Button::with_label("Change plan");
                    edit.add_css_class("chip");
                    edit.set_halign(gtk::Align::Start);
                    let (a, b2) = (app.clone(), book.clone());
                    edit.connect_clicked(move |btn| {
                        if let Some(p) = btn
                            .ancestor(gtk::Popover::static_type())
                            .and_downcast::<gtk::Popover>()
                        {
                            p.popdown();
                        }
                        crate::booksheet::plan_sheet(&a, &b2);
                    });
                    col.append(&edit);
                }
                None => {
                    let make = gtk::Button::with_label("Make a reading plan");
                    make.add_css_class("chip");
                    make.set_halign(gtk::Align::Start);
                    let (a, b2) = (app.clone(), book.clone());
                    make.connect_clicked(move |btn| {
                        if let Some(p) = btn
                            .ancestor(gtk::Popover::static_type())
                            .and_downcast::<gtk::Popover>()
                        {
                            p.popdown();
                        }
                        crate::booksheet::plan_sheet(&a, &b2);
                    });
                    col.append(&make);
                }
            }
        }
        let pop = popover(anchor, &col, true);
        pop.set_position(gtk::PositionType::Bottom);
        pop.popup();
    }
}
