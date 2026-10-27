//! The Library tab: today's reading, plans, the book to continue, collections and every book. The sidebar (or,
//! in the phone layout, the menu under the title) switches to All Books, PDFs, Finished, a collection,
//! Highlights or Plans.

use std::cell::RefCell;
use std::rc::Rc;

use gbook_core::library::{format_duration, plural, reading_streak};
use gbook_core::{Book, BookFormat, Highlight, dates, plans};
use gtk::prelude::*;
use gtk::{glib, pango};

use crate::shell::{self, ShellRc};
use crate::state::{AppRc, today};
use crate::widgets::*;
use crate::{booksheet, import, sheet};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum View {
    Overview,
    Books,
    Pdfs,
    Finished,
    Collections,
    /// The collection named by `Shell::lib_collection`.
    Collection,
    Highlights,
    Plans,
}

impl View {
    const ALL: [View; 5] = [
        View::Overview,
        View::Books,
        View::Collections,
        View::Highlights,
        View::Plans,
    ];

    fn title(self) -> &'static str {
        match self {
            View::Overview => "Library",
            View::Books => "All Books",
            View::Pdfs => "PDFs",
            View::Finished => "Finished",
            View::Collections | View::Collection => "Collections",
            View::Highlights => "Highlights",
            View::Plans => "Plans",
        }
    }

    fn icon(self) -> &'static str {
        match self {
            View::Overview => "dashboard",
            View::Books => "local-library",
            View::Pdfs => "description",
            View::Finished => "check-circle",
            View::Collections | View::Collection => "collections-bookmark",
            View::Highlights => "format-quote",
            View::Plans => "event-available",
        }
    }
}

const COVER_W: i32 = 132;

pub fn page(app: &AppRc, shell: &ShellRc) -> gtk::Widget {
    let query = Rc::new(RefCell::new(String::new()));
    let content = vbox(22);
    content.set_margin_top(76);
    content.set_margin_bottom(130);
    content.set_margin_start(28);
    content.set_margin_end(28);
    let clamp = Clamp::new(&content, 1040);

    let start = hbox(0);
    let end = hbox(10);
    let themes = circle_button("palette", "Theme");
    let a = app.clone();
    themes.connect_clicked(move |b| theme_popover(&a, b));
    let add = circle_button("add", "Add books (Ctrl+O)");
    let a = app.clone();
    add.connect_clicked(move |_| import::pick_files(&a));
    end.append(&themes);
    end.append(&add);
    let top = top_bar(&start, None, &end, false, Some((1040, 28)));

    let render: Rc<RefCell<Option<Rc<dyn Fn()>>>> = Rc::default();
    let r = {
        let (app, shell, content, start, query, _render) = (
            app.clone(),
            shell.clone(),
            content.clone(),
            start.clone(),
            query.clone(),
            render.clone(),
        );
        Rc::new(move || {
            clear(&start);
            clear(&content);
            build(&app, &shell, &content, &query);
            // The sidebar shows the profile on wide windows.
            if shell.wide.get() {
                return;
            }
            let av = gtk::Button::new();
            av.add_css_class("plain");
            av.set_child(Some(&avatar(&app, 44)));
            av.set_tooltip_text(Some("Profile"));
            let s = shell.clone();
            av.connect_clicked(move |_| s.open_settings("profile"));
            start.append(&av);
        }) as Rc<dyn Fn()>
    };
    *render.borrow_mut() = Some(r.clone());
    *shell.library_render.borrow_mut() = Some(r.clone());
    shell::scrolled_page(app, &top, &clamp.upcast(), r)
}

fn build(app: &AppRc, shell: &ShellRc, content: &gtk::Box, query: &Rc<RefCell<String>>) {
    let d = app.data();
    let books: Vec<Book> = d.books_by_recent().into_iter().cloned().collect();
    let (today_n, _) = today();
    let plan_rows: Vec<(gbook_core::ReadingPlan, Book)> = d
        .plans
        .iter()
        .filter_map(|p| {
            books
                .iter()
                .find(|b| b.id == p.book_id)
                .map(|b| (p.clone(), b.clone()))
        })
        .collect();
    let current = books.iter().find(|b| b.last_opened_at > 0).cloned();
    let mut v = shell.lib_view.get();
    let coll = if v == View::Collection {
        let id = shell.lib_collection.borrow().clone();
        d.collections.iter().find(|c| c.id == id).cloned()
    } else {
        None
    };
    if v == View::Collection && coll.is_none() {
        v = View::Collections;
    }

    // Title doubles as a menu button.
    let head = vbox(2);
    let title_btn = gtk::Button::new();
    title_btn.add_css_class("plain");
    title_btn.set_halign(gtk::Align::Start);
    let tr = hbox(10);
    let title = coll
        .as_ref()
        .map(|c| c.name.clone())
        .unwrap_or_else(|| v.title().to_string());
    let tl = label(&title, &["large-title"]);
    tl.set_ellipsize(pango::EllipsizeMode::End);
    tr.append(&tl);
    let chev = icon("keyboard-arrow-down", 22);
    let chev_box = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    chev_box.add_css_class("glass");
    chev_box.set_size_request(32, 32);
    chev_box.set_valign(gtk::Align::Center);
    chev.set_hexpand(true);
    chev_box.append(&chev);
    tr.append(&chev_box);
    title_btn.set_child(Some(&tr));
    // With the sidebar there, the title is just a title.
    if shell.wide.get() {
        chev_box.set_visible(false);
        title_btn.set_can_target(false);
    }
    let counts = [
        (View::Books, d.books.len()),
        (View::Collections, d.collections.len()),
        (View::Highlights, d.highlights.len()),
        (View::Plans, plan_rows.len()),
    ];
    {
        let shell = shell.clone();
        title_btn.connect_clicked(move |b| {
            let menu = vbox(0);
            let pop = popover(b, &menu, false);
            for item in View::ALL {
                let on = item == shell.lib_view.get();
                let row = gtk::Button::new();
                row.add_css_class("menu-row");
                if on {
                    row.add_css_class("on");
                }
                let h = hbox(14);
                h.append(&icon(item.icon(), 22));
                let l = label(item.title(), &[]);
                l.set_hexpand(true);
                h.append(&l);
                if let Some((_, n)) = counts.iter().find(|(k, _)| *k == item) {
                    h.append(&label(&n.to_string(), &["text2"]));
                }
                let check = icon("check", 20);
                check.set_opacity(if on { 1.0 } else { 0.0 });
                check.add_css_class("accent");
                h.append(&check);
                row.set_child(Some(&h));
                row.set_size_request(260, -1);
                let (shell, pop2) = (shell.clone(), pop.clone());
                row.connect_clicked(move |_| {
                    pop2.popdown();
                    shell.show_library(item);
                });
                menu.append(&row);
            }
            pop.popup();
        });
    }
    head.append(&title_btn);
    let hl_groups = highlight_groups(&d.highlights, &books);
    match v {
        View::Collections => head.append(&label(
            &plural(d.collections.len(), "collection"),
            &["secondary"],
        )),
        View::Plans => head.append(&label(&plural(plan_rows.len(), "plan"), &["secondary"])),
        View::Highlights => head.append(&label(
            &format!(
                "{} saved across {}",
                d.highlights.len(),
                plural(hl_groups.len(), "book")
            ),
            &["secondary"],
        )),
        View::Collection => {
            if let Some(c) = &coll {
                let row = hbox(8);
                row.append(&label(&plural(c.book_ids.len(), "book"), &["secondary"]));
                row.append(&spacer());
                let add = gtk::Button::with_label("Add Books");
                add.add_css_class("chip");
                let (a, id) = (app.clone(), c.id.clone());
                add.connect_clicked(move |_| booksheet::collection_sheet(&a, &id));
                row.append(&add);
                let rename = gtk::Button::with_label("Rename");
                rename.add_css_class("chip");
                let (a, id, name) = (app.clone(), c.id.clone(), c.name.clone());
                rename.connect_clicked(move |_| {
                    let (a2, id2) = (a.clone(), id.clone());
                    booksheet::name_dialog(&a, "Rename Collection", &name, "Rename", move |n| {
                        a2.update(|d| {
                            if let Some(c) = d.collections.iter_mut().find(|c| c.id == id2) {
                                c.name = n.trim().to_string();
                            }
                        })
                    });
                });
                row.append(&rename);
                let del = gtk::Button::with_label("Delete");
                del.add_css_class("chip");
                del.add_css_class("destructive");
                let (a, s, id) = (app.clone(), shell.clone(), c.id.clone());
                del.connect_clicked(move |_| {
                    a.update(|d| d.collections.retain(|c| c.id != id));
                    s.show_library(View::Collections);
                });
                row.append(&del);
                head.append(&row);
            }
        }
        _ => {}
    }
    content.append(&head);

    if v == View::Overview {
        content.append(&stats_card(app, &d, today_n));
        if !plan_rows.is_empty() {
            let col = vbox(10);
            col.append(&label("Plans", &["section-title"]));
            for (p, b) in &plan_rows {
                col.append(&plan_card(app, p, b, today_n));
            }
            content.append(&col);
        }
        if let Some(b) = &current {
            let col = vbox(12);
            col.append(&label("Continue", &["section-title"]));
            col.append(&continue_card(app, b));
            content.append(&col);
        }
        if !books.is_empty() {
            let col = vbox(12);
            let h = hbox(8);
            h.append(&label("Collections", &["section-title"]));
            if !d.collections.is_empty() {
                let n = label(&d.collections.len().to_string(), &["secondary"]);
                n.set_valign(gtk::Align::End);
                n.set_margin_bottom(3);
                h.append(&n);
            }
            col.append(&h);
            col.append(&collections_row(app, shell, &d, &books));
            content.append(&col);
        }
    }

    if v == View::Collections {
        let flow = flow_box(2, 6, 18);
        for c in &d.collections {
            let bs: Vec<Book> = c
                .book_ids
                .iter()
                .filter_map(|id| books.iter().find(|b| b.id == *id).cloned())
                .collect();
            flow.insert(&collection_card(app, shell, &c.name, &c.id, &bs), -1);
        }
        flow.insert(&new_collection_card(app, shell), -1);
        content.append(&flow);
    }

    let filtered: Option<Vec<Book>> = match v {
        View::Pdfs => Some(
            books
                .iter()
                .filter(|b| b.format == BookFormat::PDF)
                .cloned()
                .collect(),
        ),
        View::Finished => Some(
            books
                .iter()
                .filter(|b| b.progress >= 0.995)
                .cloned()
                .collect(),
        ),
        View::Collection => coll.as_ref().map(|c| {
            c.book_ids
                .iter()
                .filter_map(|id| books.iter().find(|b| b.id == *id).cloned())
                .collect()
        }),
        _ => None,
    };
    if let Some(list) = filtered {
        if v != View::Collection {
            content.append(&label(&plural(list.len(), "book"), &["secondary"]));
        }
        if list.is_empty() {
            content.append(&empty_state(
                "Nothing here yet",
                if v == View::Collection {
                    "Add books to this collection, or from a book's page."
                } else {
                    "Books show up here as you add and finish them."
                },
                None,
            ));
        } else {
            content.append(&book_grid(app, shell, &list));
        }
    }

    if v == View::Highlights {
        content.append(&highlights_view(app, &hl_groups, query));
    }

    if v == View::Plans {
        if plan_rows.is_empty() {
            content.append(&empty_state(
                "No reading plans",
                "Pick a book and a finish date. GBook works out the daily pace.",
                None,
            ));
        }
        for (p, b) in &plan_rows {
            content.append(&plan_card(app, p, b, today_n));
        }
        let unplanned: Vec<Book> = books
            .iter()
            .filter(|b| b.progress < 0.995 && !plan_rows.iter().any(|(_, pb)| pb.id == b.id))
            .cloned()
            .collect();
        if !unplanned.is_empty() {
            let b = gtk::Button::new();
            b.add_css_class("pill");
            let h = hbox(8);
            h.append(&icon("add", 20));
            h.append(&gtk::Label::new(Some("New Plan")));
            b.set_child(Some(&h));
            b.set_halign(gtk::Align::Center);
            let a = app.clone();
            b.connect_clicked(move |_| pick_plan_book(&a, &unplanned));
            content.append(&b);
        }
    }

    if v == View::Overview || v == View::Books {
        let h = hbox(8);
        if v == View::Overview {
            h.append(&label("All Books", &["section-title"]));
            let n = label(&books.len().to_string(), &["secondary"]);
            n.set_valign(gtk::Align::End);
            n.set_margin_bottom(3);
            h.append(&n);
        } else {
            h.append(&label(&plural(books.len(), "book"), &["secondary"]));
        }
        content.append(&h);
        if books.is_empty() {
            let a = app.clone();
            content.append(&empty_state(
                "Your library is empty",
                "Add EPUB or PDF books, or drop them onto this window.",
                Some(("Add Books", Rc::new(move || import::pick_files(&a)))),
            ));
        } else {
            content.append(&book_grid(app, shell, &books));
        }
    }
}

fn flow_box(min: u32, max: u32, spacing: u32) -> gtk::FlowBox {
    let f = gtk::FlowBox::new();
    f.set_selection_mode(gtk::SelectionMode::None);
    f.set_homogeneous(true);
    f.set_min_children_per_line(min);
    f.set_max_children_per_line(max);
    f.set_column_spacing(spacing);
    f.set_row_spacing(spacing + 6);
    f.set_valign(gtk::Align::Start);
    f
}

fn book_grid(app: &AppRc, shell: &ShellRc, books: &[Book]) -> gtk::Widget {
    let flow = flow_box(2, 14, 22);
    flow.set_activate_on_single_click(true);
    // The first rows go in now; the rest after the first frame, so a big library doesn't delay the window.
    const FIRST: usize = 12;
    for b in books.iter().take(FIRST) {
        flow.insert(&book_tile(app, shell, b), -1);
    }
    if books.len() > FIRST {
        let rest: Vec<Book> = books[FIRST..].to_vec();
        let (app, shell, f) = (app.clone(), shell.clone(), flow.downgrade());
        let mut i = 0;
        glib::idle_add_local(move || {
            let Some(flow) = f.upgrade() else {
                return glib::ControlFlow::Break;
            };
            for b in rest.iter().skip(i).take(16) {
                flow.insert(&book_tile(&app, &shell, b), -1);
            }
            i += 16;
            if i >= rest.len() {
                glib::ControlFlow::Break
            } else {
                glib::ControlFlow::Continue
            }
        });
    }
    let s = shell.clone();
    flow.connect_child_activated(move |_, child| {
        let id = child.widget_name().to_string();
        s.open_book(&id, None);
    });
    flow.upcast()
}

fn book_tile(app: &AppRc, shell: &ShellRc, b: &Book) -> gtk::FlowBoxChild {
    {
        let item = vbox(8);
        item.set_halign(gtk::Align::Center);
        let ov = gtk::Overlay::new();
        ov.set_child(Some(&Cover::new(app, b, COVER_W)));
        if let Some(r) = &b.my_reaction {
            let e = label(r, &["emoji-dot"]);
            e.set_halign(gtk::Align::End);
            e.set_valign(gtk::Align::Start);
            e.set_margin_top(5);
            e.set_margin_end(5);
            ov.add_overlay(&e);
        }
        item.append(&ov);
        let meta = hbox(6);
        let (text, new) = if b.progress >= 0.995 {
            ("Finished".to_string(), false)
        } else if b.progress > 0.0 {
            (format!("{}%", ((b.progress * 100.0) as i32).max(1)), false)
        } else {
            ("NEW".to_string(), true)
        };
        let l = label(
            &text,
            &["caption", "bold", if new { "accent" } else { "text2" }],
        );
        meta.append(&l);
        if b.format == BookFormat::PDF {
            meta.append(&label("PDF", &["badge"]));
        }
        meta.append(&spacer());
        let more = gtk::Button::new();
        more.add_css_class("flat");
        more.set_child(Some(&icon("more-horiz", 20)));
        more.set_tooltip_text(Some("Details"));
        let (a, s, id) = (app.clone(), shell.clone(), b.id.clone());
        more.connect_clicked(move |_| booksheet::show(&a, &s, &id));
        meta.append(&more);
        meta.set_size_request(COVER_W, -1);
        item.append(&meta);
        item.set_tooltip_text(Some(&format!("{}\n{}", b.title, b.author)));
        let child = gtk::FlowBoxChild::new();
        child.set_child(Some(&item));
        child.set_widget_name(&b.id);
        // Right-click for the book's details, as the "…" button does.
        let rc = gtk::GestureClick::new();
        rc.set_button(3);
        let (a, s, id) = (app.clone(), shell.clone(), b.id.clone());
        rc.connect_pressed(move |_, _, _, _| booksheet::show(&a, &s, &id));
        child.add_controller(rc);
        child
    }
}

/// Today's reading time, a 7-day bar chart and week / streak / total figures.
fn stats_card(app: &AppRc, d: &gbook_core::LibraryData, today_n: i64) -> gtk::Widget {
    let card = vbox(14);
    card.add_css_class("glass");
    let inner = vbox(14);
    inner.set_margin_top(18);
    inner.set_margin_bottom(18);
    inner.set_margin_start(20);
    inner.set_margin_end(20);
    let days: Vec<i64> = (0..7).rev().map(|i| today_n - i).collect();
    let values: Vec<i64> = days
        .iter()
        .map(|day| d.reading_log.get(&dates::key(*day)).copied().unwrap_or(0))
        .collect();
    let max = values.iter().copied().max().unwrap_or(0).max(15 * 60_000);
    let top = hbox(16);
    let left = vbox(2);
    left.set_hexpand(true);
    let eyebrow = hbox(2);
    eyebrow.append(&label("READ TODAY", &["eyebrow"]));
    let chev = icon("keyboard-arrow-right", 18);
    chev.add_css_class("text2");
    eyebrow.append(&chev);
    left.append(&eyebrow);
    let big = label(&format_duration(values[6]), &["serif-title"]);
    big.set_attributes(Some(&size_attr(32)));
    left.append(&big);
    let goal = d.settings.daily_goal_min;
    if goal > 0 {
        let goal_ms = goal as i64 * 60_000;
        let reached = values[6] >= goal_ms;
        let g = vbox(4);
        g.set_margin_top(6);
        g.set_margin_end(16);
        g.append(&progress(values[6] as f32 / goal_ms as f32, true));
        let extra = (values[6] - goal_ms) / 60_000;
        let text = if reached {
            format!(
                "Goal reached 🎉{}",
                if extra > 0 {
                    format!(" · +{extra} min")
                } else {
                    String::new()
                }
            )
        } else {
            format!("of {goal} min goal")
        };
        g.append(&label(
            &text,
            &[
                "caption",
                if reached { "accent" } else { "text2" },
                if reached { "semibold" } else { "x" },
            ],
        ));
        left.append(&g);
    }
    top.append(&left);
    let chart = hbox(7);
    chart.set_valign(gtk::Align::End);
    for (i, day) in days.iter().enumerate() {
        let col = vbox(4);
        let slot = gtk::Box::new(gtk::Orientation::Vertical, 0);
        slot.set_size_request(15, 50);
        let frac = values[i] as f32 / max as f32;
        let bar = gtk::Box::new(gtk::Orientation::Vertical, 0);
        bar.add_css_class("daybar");
        if i == 6 {
            bar.add_css_class("today");
        }
        if values[i] == 0 {
            bar.add_css_class("empty");
        }
        bar.set_size_request(15, ((frac * 50.0).round() as i32).max(4));
        bar.set_valign(gtk::Align::End);
        bar.set_vexpand(true);
        slot.append(&bar);
        col.append(&slot);
        let dl = label(
            dates::narrow_weekday(*day),
            &["small", if i == 6 { "bold" } else { "text2" }],
        );
        dl.set_xalign(0.5);
        col.append(&dl);
        chart.append(&col);
    }
    top.append(&chart);
    inner.append(&top);
    let stats = hbox(0);
    stats.set_homogeneous(true);
    let streak = reading_streak(&d.reading_log, today_n);
    let total: i64 = d.books.iter().map(|b| b.reading_ms).sum();
    for (value, name) in [
        (format_duration(values.iter().sum()), "This week"),
        (
            if streak == 1 {
                "1 day".to_string()
            } else {
                format!("{streak} days")
            },
            "Streak",
        ),
        (format_duration(total), "All time"),
    ] {
        let c = vbox(0);
        c.append(&label(&value, &["semibold"]));
        c.append(&label(name, &["caption", "text2"]));
        stats.append(&c);
    }
    inner.append(&stats);
    card.append(&inner);
    let b = gtk::Button::new();
    b.add_css_class("card-button");
    b.set_child(Some(&card));
    b.set_tooltip_text(Some("Reading activity"));
    let a = app.clone();
    b.connect_clicked(move |_| activity_sheet(&a));
    b.upcast()
}

fn size_attr(px: i32) -> pango::AttrList {
    let a = pango::AttrList::new();
    a.insert(pango::AttrSize::new_size_absolute(px * pango::SCALE));
    a
}

/// Reading time per book over the last week and in total.
fn activity_sheet(app: &AppRc) {
    let d = app.data();
    let (today_n, _) = today();
    let col = vbox(10);
    col.set_margin_start(24);
    col.set_margin_end(24);
    col.set_margin_bottom(24);
    let mut week: Vec<(String, i64)> = Vec::new();
    for i in 0..7 {
        if let Some(m) = d.book_days.get(&dates::key(today_n - i)) {
            for (id, ms) in m {
                match week.iter_mut().find(|(k, _)| k == id) {
                    Some((_, t)) => *t += ms,
                    None => week.push((id.clone(), *ms)),
                }
            }
        }
    }
    week.sort_by_key(|(_, t)| std::cmp::Reverse(*t));
    let pages_week: i32 = (0..7)
        .filter_map(|i| d.pages_log.get(&dates::key(today_n - i)))
        .sum();
    col.append(&label(
        &format!("{} pages read in the last 7 days", pages_week),
        &["secondary"],
    ));
    col.append(&label("This week", &["section-title"]));
    if week.is_empty() {
        col.append(&label("No reading in the last 7 days yet.", &["secondary"]));
    }
    for (id, ms) in &week {
        if let Some(b) = d.book(id) {
            col.append(&book_row(
                app,
                b,
                &format!(
                    "{} this week · {} in total",
                    format_duration(*ms),
                    format_duration(b.reading_ms)
                ),
            ));
        }
    }
    drop(d);
    sheet::present(app, Some("Reading Activity"), &col, 460);
}

/// Small cover with a title and a line of detail, for lists.
pub fn book_row(app: &AppRc, b: &Book, detail: &str) -> gtk::Box {
    let row = hbox(14);
    row.append(&Cover::new(app, b, 40));
    let t = vbox(2);
    t.set_valign(gtk::Align::Center);
    t.set_hexpand(true);
    t.append(&wrap_label(&b.title, &["semibold"], 2));
    t.append(&wrap_label(detail, &["caption", "text2"], 1));
    row.append(&t);
    row
}

/// Last-read book: a glass card with its cover, progress and time read.
fn continue_card(app: &AppRc, b: &Book) -> gtk::Widget {
    let card = hbox(16);
    card.add_css_class("glass");
    let inner = hbox(16);
    inner.set_margin_top(14);
    inner.set_margin_bottom(14);
    inner.set_margin_start(14);
    inner.set_margin_end(18);
    inner.set_hexpand(true);
    inner.append(&Cover::new(app, b, 60));
    let col = vbox(2);
    col.set_hexpand(true);
    col.set_valign(gtk::Align::Center);
    let t = wrap_label(&b.title, &["serif-title"], 2);
    t.set_attributes(Some(&size_attr(19)));
    col.append(&t);
    col.append(&label(&b.author, &["secondary"]));
    let p = progress(b.progress.max(0.01), false);
    p.set_margin_top(10);
    col.append(&p);
    let mut detail = format!("{}%", (b.progress * 100.0) as i32);
    if b.reading_ms > 60_000 {
        detail += &format!(" · {} read", format_duration(b.reading_ms));
    }
    detail += " · Click to continue";
    let dl = label(&detail, &["caption", "text2"]);
    dl.set_margin_top(6);
    col.append(&dl);
    inner.append(&col);
    card.append(&inner);
    let btn = gtk::Button::new();
    btn.add_css_class("card-button");
    btn.set_child(Some(&card));
    let (a, id) = (app.clone(), b.id.clone());
    btn.connect_clicked(move |_| a.open(&id, None));
    btn.upcast()
}

pub fn plan_card(app: &AppRc, p: &gbook_core::ReadingPlan, b: &Book, today_n: i64) -> gtk::Widget {
    let st = plans::status(p, b, today_n);
    let (tag, color) = if st.done {
        ("Done 🎉", "accent")
    } else if st.overdue {
        ("Overdue", "destructive")
    } else if st.on_track(b.progress) {
        ("On track", "ontrack")
    } else {
        ("Behind", "behind")
    };
    let card = hbox(14);
    card.add_css_class("glass");
    let inner = hbox(14);
    inner.set_margin_top(14);
    inner.set_margin_bottom(14);
    inner.set_margin_start(14);
    inner.set_margin_end(18);
    inner.set_hexpand(true);
    inner.append(&Cover::new(app, b, 44));
    let col = vbox(2);
    col.set_hexpand(true);
    col.set_valign(gtk::Align::Center);
    let h = hbox(8);
    let t = label(&b.title, &["semibold"]);
    t.set_ellipsize(pango::EllipsizeMode::End);
    h.append(&t);
    let tg = label(tag, &["tag", color]);
    h.append(&tg);
    col.append(&h);
    let line = if st.done {
        "Finished".to_string()
    } else {
        format!(
            "Finish by {} · {} · {}",
            plans::short_date(&p.target_date),
            st.due_label(),
            plans::pace_text(b, st.per_day)
        )
    };
    let l = label(&line, &["secondary"]);
    l.set_ellipsize(pango::EllipsizeMode::End);
    col.append(&l);
    let bar = progress(b.progress.clamp(0.02, 1.0), true);
    bar.set_margin_top(10);
    col.append(&bar);
    inner.append(&col);
    card.append(&inner);
    let btn = gtk::Button::new();
    btn.add_css_class("card-button");
    btn.set_child(Some(&card));
    let (a, book) = (app.clone(), b.clone());
    btn.connect_clicked(move |_| booksheet::plan_sheet(&a, &book));
    btn.upcast()
}

fn pick_plan_book(app: &AppRc, books: &[Book]) {
    let col = vbox(4);
    col.set_margin_start(12);
    col.set_margin_end(12);
    col.set_margin_bottom(16);
    let close: Rc<RefCell<Option<sheet::Close>>> = Rc::default();
    for b in books {
        let btn = gtk::Button::new();
        btn.add_css_class("menu-row");
        btn.set_child(Some(&book_row(
            app,
            b,
            &format!(
                "{}{}",
                b.author,
                if b.progress > 0.0 {
                    format!(" · {}%", (b.progress * 100.0) as i32)
                } else {
                    String::new()
                }
            ),
        )));
        let (a, book, c) = (app.clone(), b.clone(), close.clone());
        btn.connect_clicked(move |_| {
            if let Some(c) = c.borrow().as_ref() {
                c();
            }
            booksheet::plan_sheet(&a, &book);
        });
        col.append(&btn);
    }
    *close.borrow_mut() = Some(sheet::present(app, Some("Plan which book?"), &col, 460));
}

fn collections_row(
    app: &AppRc,
    shell: &ShellRc,
    d: &gbook_core::LibraryData,
    books: &[Book],
) -> gtk::Widget {
    let row = hbox(16);
    row.set_margin_bottom(14);
    row.set_margin_start(2);
    for c in &d.collections {
        let bs: Vec<Book> = c
            .book_ids
            .iter()
            .filter_map(|id| books.iter().find(|b| b.id == *id).cloned())
            .collect();
        row.append(&collection_card(app, shell, &c.name, &c.id, &bs));
    }
    row.append(&new_collection_card(app, shell));
    let sc = gtk::ScrolledWindow::new();
    sc.set_vscrollbar_policy(gtk::PolicyType::Never);
    sc.set_propagate_natural_height(true);
    sc.set_child(Some(&row));
    sc.upcast()
}

/// A collection's first covers fanned out on a glass card, with its name and size.
fn collection_card(
    app: &AppRc,
    shell: &ShellRc,
    name: &str,
    id: &str,
    books: &[Book],
) -> gtk::Widget {
    let card = vbox(10);
    let art = gtk::Box::new(gtk::Orientation::Vertical, 0);
    art.add_css_class("glass");
    art.set_size_request(196, 150);
    let fixed = gtk::Fixed::new();
    fixed.set_halign(gtk::Align::Center);
    fixed.set_valign(gtk::Align::End);
    fixed.set_vexpand(true);
    let shown: Vec<&Book> = books.iter().take(3).collect();
    for (i, b) in shown.iter().enumerate().rev() {
        let c = Cover::new(app, b, 78);
        fixed.put(&c, 12.0 + i as f64 * 40.0, 14.0 + i as f64 * 6.0);
    }
    fixed.set_size_request(12 + 78 + 40 * (shown.len().max(1) as i32 - 1) + 12, 140);
    art.append(&fixed);
    art.set_overflow(gtk::Overflow::Hidden);
    card.append(&art);
    card.append(&label(name, &["semibold"]));
    card.append(&label(&plural(books.len(), "book"), &["caption", "text2"]));
    let btn = gtk::Button::new();
    btn.add_css_class("card-button");
    btn.set_child(Some(&card));
    let (s, id) = (shell.clone(), id.to_string());
    btn.connect_clicked(move |_| s.show_collection(&id));
    btn.upcast()
}

fn new_collection_card(app: &AppRc, shell: &ShellRc) -> gtk::Widget {
    let card = vbox(10);
    let art = gtk::Box::new(gtk::Orientation::Vertical, 8);
    art.add_css_class("glass");
    art.set_size_request(196, 150);
    let plus = icon("add", 30);
    plus.set_vexpand(true);
    plus.add_css_class("accent");
    art.append(&plus);
    card.append(&art);
    card.append(&label("New Collection", &["semibold"]));
    card.append(&label(" ", &["caption"]));
    let btn = gtk::Button::new();
    btn.add_css_class("card-button");
    btn.set_child(Some(&card));
    let (a, s) = (app.clone(), shell.clone());
    btn.connect_clicked(move |_| {
        let (a2, s2) = (a.clone(), s.clone());
        booksheet::name_dialog(&a, "New Collection", "", "Create", move |name| {
            let id = a2.update(|d| d.create_collection(&name, Vec::new()));
            s2.show_collection(&id);
        });
    });
    btn.upcast()
}

fn highlight_groups(all: &[Highlight], books: &[Book]) -> Vec<(Book, Vec<Highlight>)> {
    let mut hs: Vec<&Highlight> = all.iter().collect();
    hs.sort_by_key(|h| std::cmp::Reverse(h.created_at));
    let mut out: Vec<(Book, Vec<Highlight>)> = Vec::new();
    for h in hs {
        match out.iter_mut().find(|(b, _)| b.id == h.book_id) {
            Some((_, v)) => v.push(h.clone()),
            None => {
                if let Some(b) = books.iter().find(|b| b.id == h.book_id) {
                    out.push((b.clone(), vec![h.clone()]));
                }
            }
        }
    }
    out
}

fn highlights_view(
    app: &AppRc,
    groups: &[(Book, Vec<Highlight>)],
    query: &Rc<RefCell<String>>,
) -> gtk::Widget {
    let col = vbox(14);
    if groups.is_empty() {
        col.append(&empty_state(
            "No highlights yet",
            "Select text while reading to highlight it.",
            None,
        ));
        return col.upcast();
    }
    let entry = gtk::SearchEntry::new();
    entry.set_placeholder_text(Some("Search books or words in highlights"));
    entry.set_text(&query.borrow());
    col.append(&entry);
    let results = vbox(14);
    col.append(&results);
    let fill = {
        let (app, groups, results) = (app.clone(), groups.to_vec(), results.clone());
        move |term: &str| {
            clear(&results);
            let t = term.trim().to_lowercase();
            for (b, hs) in &groups {
                let hits: Vec<&Highlight> = if t.is_empty()
                    || b.title.to_lowercase().contains(&t)
                    || b.author.to_lowercase().contains(&t)
                {
                    hs.iter().collect()
                } else {
                    hs.iter()
                        .filter(|h| {
                            h.text.to_lowercase().contains(&t)
                                || h.note
                                    .as_ref()
                                    .is_some_and(|n| n.to_lowercase().contains(&t))
                        })
                        .collect()
                };
                if hits.is_empty() {
                    continue;
                }
                results.append(&book_row(&app, b, &plural(hits.len(), "highlight")));
                for h in hits.iter().take(if t.is_empty() { 3 } else { 50 }) {
                    results.append(&highlight_quote(&app, b, h));
                }
            }
            if results.first_child().is_none() {
                results.append(&empty_state(
                    "No matches",
                    &format!("No book or highlight mentions “{}”.", term.trim()),
                    None,
                ));
            }
        }
    };
    fill(&query.borrow());
    let q = query.clone();
    entry.connect_search_changed(move |e| {
        *q.borrow_mut() = e.text().to_string();
        fill(&e.text());
    });
    col.upcast()
}

/// A saved highlight as a quote card; clicking opens the book at it.
pub fn highlight_quote(app: &AppRc, b: &Book, h: &Highlight) -> gtk::Widget {
    let card = vbox(6);
    card.add_css_class("card");
    let inner = vbox(6);
    inner.set_margin_top(14);
    inner.set_margin_bottom(14);
    inner.set_margin_start(18);
    inner.set_margin_end(18);
    let bar = hbox(12);
    let stripe = gtk::Box::new(gtk::Orientation::Vertical, 0);
    stripe.set_size_request(4, -1);
    stripe.add_css_class(&format!("hl-stripe-{}", h.color.rem_euclid(5)));
    bar.append(&stripe);
    let q = wrap_label(&format!("“{}”", h.text), &["quote"], 6);
    q.set_hexpand(true);
    bar.append(&q);
    if let Some(r) = &h.reaction {
        let e = label(r, &[]);
        e.set_valign(gtk::Align::Start);
        bar.append(&e);
    }
    inner.append(&bar);
    if let Some(n) = h.note.as_ref().filter(|n| !n.trim().is_empty()) {
        let nl = wrap_label(n, &["secondary"], 4);
        nl.set_margin_start(16);
        inner.append(&nl);
    }
    card.append(&inner);
    let btn = gtk::Button::new();
    btn.add_css_class("card-button");
    btn.set_child(Some(&card));
    let (a, id, at) = (app.clone(), b.id.clone(), (h.chapter, h.block));
    btn.connect_clicked(move |_| a.open(&id, Some(at)));
    btn.upcast()
}

pub fn empty_state(title: &str, body: &str, action: Option<(&str, Rc<dyn Fn()>)>) -> gtk::Widget {
    let col = vbox(8);
    col.set_margin_top(40);
    col.set_margin_bottom(40);
    col.set_halign(gtk::Align::Center);
    let t = label(title, &["section-title"]);
    t.set_xalign(0.5);
    col.append(&t);
    let b = wrap_label(body, &["secondary"], 3);
    b.set_xalign(0.5);
    b.set_justify(gtk::Justification::Center);
    b.set_max_width_chars(48);
    col.append(&b);
    if let Some((text, f)) = action {
        let btn = gtk::Button::with_label(text);
        btn.add_css_class("primary");
        btn.set_halign(gtk::Align::Center);
        btn.set_margin_top(12);
        btn.connect_clicked(move |_| f());
        col.append(&btn);
    }
    col.upcast()
}

/// Accent palette, light/dark and pure black, from the palette button.
fn theme_popover(app: &AppRc, parent: &gtk::Button) {
    let col = vbox(14);
    col.set_margin_top(8);
    col.set_margin_bottom(8);
    col.set_margin_start(8);
    col.set_margin_end(8);
    col.append(&label("Theme", &["sheet-title"]));
    col.append(&crate::settings::appearance_controls(app));
    let pop = popover(parent, &col, true);
    pop.set_position(gtk::PositionType::Bottom);
    pop.popup();
}
