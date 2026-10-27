//! A book's page (cover, rating, reactions, Read, and cards for highlights, plan, collections, posts, export
//! and removal), plus the sheets it leads to.

use std::cell::RefCell;
use std::rc::Rc;

use gbook_core::library::{format_duration, plural};
use gbook_core::{Book, BookFormat, REACTION_EMOJIS, ReadingPlan, dates, plans};
use gtk::prelude::*;
use gtk::{gio, glib};

use crate::library::{book_row, empty_state, highlight_quote};
use crate::shell::ShellRc;
use crate::state::{AppRc, today};
use crate::widgets::*;
use crate::{feed, sheet};

pub fn show(app: &AppRc, shell: &ShellRc, id: &str) {
    let (app2, shell, id) = (app.clone(), shell.clone(), id.to_string());
    sheet::present_live(app, None, 480, move |content, close| {
        let app = &app2;
        let d = app.data();
        let Some(book) = d.book(&id).cloned() else {
            let c = close.clone();
            glib::idle_add_local_once(move || c());
            return;
        };
        let col = vbox(0);
        col.set_margin_start(24);
        col.set_margin_end(24);
        col.set_margin_bottom(24);
        let cover = Cover::new(app, &book, 150);
        cover.set_halign(gtk::Align::Center);
        col.append(&cover);
        let t = wrap_label(&book.title, &["serif-title"], 3);
        t.set_attributes(Some(&{
            let a = gtk::pango::AttrList::new();
            a.insert(gtk::pango::AttrSize::new_size_absolute(
                25 * gtk::pango::SCALE,
            ));
            a
        }));
        t.set_xalign(0.5);
        t.set_justify(gtk::Justification::Center);
        t.set_margin_top(20);
        col.append(&t);
        let au = label(&book.author, &["secondary"]);
        au.set_xalign(0.5);
        au.set_margin_top(4);
        col.append(&au);
        let mut meta = vec![if book.format == BookFormat::PDF {
            "PDF".to_string()
        } else {
            "Book".to_string()
        }];
        if book.page_count > 0 {
            meta.push(format!("{} pages", book.page_count));
        }
        meta.push(format!("{}% read", (book.progress * 100.0) as i32));
        if book.reading_ms > 60_000 {
            meta.push(format_duration(book.reading_ms));
        }
        let m = label(&meta.join(" · "), &["caption", "text2"]);
        m.set_xalign(0.5);
        m.set_margin_top(4);
        col.append(&m);

        // Five stars; clicking the current rating again clears it.
        let stars = hbox(2);
        stars.set_halign(gtk::Align::Center);
        stars.set_margin_top(16);
        for n in 1..=5 {
            let on = n <= book.rating;
            let b = gtk::Button::new();
            b.add_css_class("flat");
            b.add_css_class(if on { "star-on" } else { "star-off" });
            b.set_child(Some(&icon(if on { "star" } else { "star-border" }, 30)));
            b.set_tooltip_text(Some(&format!("{n} star{}", if n > 1 { "s" } else { "" })));
            let (a, bid) = (app.clone(), book.id.clone());
            b.connect_clicked(move |_| a.update(|d| d.rate_book(&bid, n)));
            stars.append(&b);
        }
        col.append(&stars);
        let rl = label(
            if book.rating == 0 {
                "Click a star to rate"
            } else {
                "Your rating"
            },
            &["small", "text2"],
        );
        rl.set_xalign(0.5);
        col.append(&rl);
        let reacts = hbox(6);
        reacts.set_halign(gtk::Align::Center);
        reacts.set_margin_top(14);
        for e in REACTION_EMOJIS {
            let b = gtk::Button::with_label(e);
            b.add_css_class("emoji-button");
            if book.my_reaction.as_deref() == Some(e) {
                b.add_css_class("on");
            }
            let (a, bid) = (app.clone(), book.id.clone());
            b.connect_clicked(move |_| a.update(|d| d.react_book(&bid, e)));
            reacts.append(&b);
        }
        col.append(&reacts);
        let read = gtk::Button::with_label(if book.progress > 0.0 {
            "Continue Reading"
        } else {
            "Read"
        });
        read.add_css_class("primary");
        read.set_margin_top(20);
        let (a, bid, c) = (app.clone(), book.id.clone(), close.clone());
        read.connect_clicked(move |_| {
            c();
            a.open(&bid, None);
        });
        col.append(&read);

        let hl = d.highlights.iter().filter(|h| h.book_id == id).count();
        let plan = d.plans.iter().find(|p| p.book_id == id).cloned();
        let cols: Vec<String> = d
            .collections
            .iter()
            .filter(|c| c.book_ids.contains(&id))
            .map(|c| c.name.clone())
            .collect();
        let posts = d.posts.iter().filter(|p| p.book_id == id).count();
        let (today_n, _) = today();
        drop(d);
        let grid = gtk::Grid::new();
        grid.set_column_spacing(10);
        grid.set_row_spacing(10);
        grid.set_column_homogeneous(true);
        grid.set_margin_top(16);
        let cards: Vec<(&str, &str, String, Rc<dyn Fn()>, bool)> = vec![
            (
                "format-quote",
                "Highlights",
                if hl > 0 {
                    plural(hl, "highlight")
                } else {
                    "None yet".into()
                },
                {
                    let (a, b) = (app.clone(), book.clone());
                    Rc::new(move || highlights_sheet(&a, &b))
                },
                false,
            ),
            (
                "event-available",
                "Reading plan",
                plan.map(|p| {
                    let st = plans::status(&p, &book, today_n);
                    if st.done {
                        "Done".into()
                    } else {
                        st.due_label()
                    }
                })
                .unwrap_or_else(|| "Set a goal".into()),
                {
                    let (a, b) = (app.clone(), book.clone());
                    Rc::new(move || plan_sheet(&a, &b))
                },
                false,
            ),
            (
                "collections-bookmark",
                "Collections",
                if cols.is_empty() {
                    "Add to one".into()
                } else {
                    cols.join(", ")
                },
                {
                    let (a, b) = (app.clone(), book.id.clone());
                    Rc::new(move || add_to_collection_sheet(&a, vec![b.clone()]))
                },
                false,
            ),
            (
                "auto-awesome",
                "Create posts",
                plural(posts, "post"),
                {
                    let (a, b) = (app.clone(), book.id.clone());
                    Rc::new(move || feed::generate_sheet(&a, Some(&b)))
                },
                false,
            ),
            (
                "file-upload",
                "Save a copy",
                "Export the file".into(),
                {
                    let (a, b) = (app.clone(), book.clone());
                    Rc::new(move || export_book(&a, &b))
                },
                false,
            ),
            (
                "delete-outline",
                "Remove",
                "From your library".into(),
                {
                    let (a, b, c, s) = (app.clone(), book.clone(), close.clone(), shell.clone());
                    Rc::new(move || confirm_remove(&a, &s, &b, c.clone()))
                },
                true,
            ),
        ];
        for (i, (ic, name, value, f, danger)) in cards.into_iter().enumerate() {
            grid.attach(
                &option_card(ic, name, &value, danger, f),
                (i % 2) as i32,
                (i / 2) as i32,
                1,
                1,
            );
        }
        col.append(&grid);
        content.append(&col);
    });
}

/// One of the book's options as a solid card: icon, label and its current value.
fn option_card(ic: &str, name: &str, value: &str, danger: bool, f: Rc<dyn Fn()>) -> gtk::Widget {
    let b = gtk::Button::new();
    b.add_css_class("option-card");
    if danger {
        b.add_css_class("danger");
    }
    let v = vbox(2);
    let tile = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    tile.add_css_class("icon-tile");
    tile.set_halign(gtk::Align::Start);
    let i = icon(ic, 21);
    i.set_hexpand(true);
    tile.append(&i);
    v.append(&tile);
    let n = label(name, &["semibold"]);
    n.set_margin_top(18);
    n.set_ellipsize(gtk::pango::EllipsizeMode::End);
    v.append(&n);
    let val = label(value, &["caption", "text2"]);
    val.set_ellipsize(gtk::pango::EllipsizeMode::End);
    v.append(&val);
    b.set_child(Some(&v));
    b.connect_clicked(move |_| f());
    b.upcast()
}

fn confirm_remove(app: &AppRc, shell: &ShellRc, book: &Book, close_book: sheet::Close) {
    let col = vbox(16);
    col.set_margin_start(24);
    col.set_margin_end(24);
    col.set_margin_bottom(24);
    col.append(&wrap_label("Its highlights and posts are removed too. Save a copy or back up first if you want to keep them.", &["secondary"], 4));
    let row = hbox(10);
    row.set_halign(gtk::Align::End);
    let cancel = gtk::Button::with_label("Cancel");
    cancel.add_css_class("pill");
    let remove = gtk::Button::with_label("Remove");
    remove.add_css_class("primary");
    remove.add_css_class("danger");
    row.append(&cancel);
    row.append(&remove);
    col.append(&row);
    let close = sheet::present(app, Some(&format!("Remove “{}”?", book.title)), &col, 420);
    let c = close.clone();
    cancel.connect_clicked(move |_| c());
    let (a, s, id) = (app.clone(), shell.clone(), book.id.clone());
    remove.connect_clicked(move |_| {
        close();
        close_book();
        s.close_reader();
        a.update(|d| d.remove_book(&id));
        a.forget_content(&id);
        gbook_core::store::delete_book_files(&a.paths, &id);
        a.toast("Book removed");
    });
}

fn export_book(app: &AppRc, book: &Book) {
    let ext = if book.format == BookFormat::PDF {
        "pdf"
    } else {
        "epub"
    };
    let name = format!("{}.{ext}", book.title.replace(['/', '\\'], "-"));
    let dialog = gtk::FileDialog::builder()
        .title("Save a Copy")
        .initial_name(name)
        .modal(true)
        .build();
    let (a, src) = (app.clone(), app.paths.book_file(book));
    let win = app.window();
    dialog.save(win.as_ref(), gio::Cancellable::NONE, move |res| {
        let Some(dest) = res.ok().and_then(|f| f.path()) else {
            return;
        };
        match std::fs::copy(&src, &dest) {
            Ok(_) => a.toast("Saved a copy"),
            Err(e) => a.toast(&format!("Save failed: {e}")),
        }
    });
}

/// Every highlight in a book, newest first.
pub fn highlights_sheet(app: &AppRc, book: &Book) {
    let (a, b) = (app.clone(), book.clone());
    sheet::present_live(app, Some(&book.title), 560, move |content, close| {
        let d = a.data();
        let mut hs: Vec<_> = d
            .highlights
            .iter()
            .filter(|h| h.book_id == b.id)
            .cloned()
            .collect();
        drop(d);
        hs.sort_by_key(|h| (h.chapter, h.block, h.start));
        let col = vbox(10);
        col.set_margin_start(24);
        col.set_margin_end(24);
        col.set_margin_bottom(24);
        col.append(&label(&plural(hs.len(), "highlight"), &["secondary"]));
        if hs.is_empty() {
            col.append(&empty_state(
                "No highlights yet",
                "Select text while reading to highlight it.",
                None,
            ));
        }
        let content_ref = a.content(&b.id);
        let mut last_ch = -1;
        for h in &hs {
            if h.chapter != last_ch {
                last_ch = h.chapter;
                let title = content_ref
                    .as_ref()
                    .and_then(|c| c.chapters.get(h.chapter as usize))
                    .map(|c| c.title.clone())
                    .unwrap_or_default();
                if !title.is_empty() {
                    let l = label(&title, &["eyebrow"]);
                    l.set_margin_top(8);
                    col.append(&l);
                }
            }
            let q = highlight_quote(&a, &b, h);
            if let Some(btn) = q.downcast_ref::<gtk::Button>() {
                let c = close.clone();
                btn.connect_clicked(move |_| c());
            }
            col.append(&q);
        }
        content.append(&col);
    });
}

/// Finish-by date for a book, with the daily pace it implies.
pub fn plan_sheet(app: &AppRc, book: &Book) {
    let existing = app
        .data()
        .plans
        .iter()
        .find(|p| p.book_id == book.id)
        .cloned();
    let (today_n, today_key) = today();
    let col = vbox(14);
    col.set_margin_start(24);
    col.set_margin_end(24);
    col.set_margin_bottom(24);
    col.append(&book_row(
        app,
        book,
        &format!("{}% read", (book.progress * 100.0) as i32),
    ));
    col.append(&label("Finish by", &["eyebrow"]));
    let cal = gtk::Calendar::new();
    let target = existing
        .as_ref()
        .and_then(|p| dates::parse(&p.target_date))
        .unwrap_or(today_n + 30);
    let (y, m, d) = dates::civil_from_days(target);
    if let Ok(dt) = glib::DateTime::from_local(y, m as i32, d as i32, 12, 0, 0.0) {
        cal.select_day(&dt);
    }
    col.append(&cal);
    let pace = label("", &["secondary"]);
    col.append(&pace);
    let picked = Rc::new(RefCell::new(target));
    let update_pace = {
        let (pace, book, picked) = (pace.clone(), book.clone(), picked.clone());
        let today_key = today_key.clone();
        move || {
            let t = *picked.borrow();
            if t < today_n {
                pace.set_text("Pick a day from today on.");
                return;
            }
            let p = ReadingPlan {
                book_id: book.id.clone(),
                target_date: dates::key(t),
                start_date: today_key.clone(),
                start_progress: book.progress,
                ..Default::default()
            };
            let st = plans::status(&p, &book, today_n);
            pace.set_text(&format!(
                "{} · {}",
                st.due_label(),
                plans::pace_text(&book, st.per_day)
            ));
        }
    };
    update_pace();
    let (p2, up) = (picked.clone(), update_pace.clone());
    cal.connect_day_selected(move |c| {
        let dt = c.date();
        *p2.borrow_mut() =
            dates::days_from_civil(dt.year(), dt.month() as u32, dt.day_of_month() as u32);
        up();
    });
    let row = hbox(10);
    row.set_halign(gtk::Align::End);
    let close_cell: Rc<RefCell<Option<sheet::Close>>> = Rc::default();
    if existing.is_some() {
        let del = gtk::Button::with_label("Delete Plan");
        del.add_css_class("pill");
        del.add_css_class("destructive");
        row.append(&del);
        let (a, id, cc) = (app.clone(), book.id.clone(), close_cell.clone());
        del.connect_clicked(move |_| {
            a.update(|d| {
                d.plans.retain(|p| p.book_id != id);
                let rid = format!("plan-{id}");
                d.reminders.retain(|r| r.id != rid);
            });
            if let Some(c) = cc.borrow().as_ref() {
                c();
            }
        });
    }
    let save = gtk::Button::with_label("Save Plan");
    save.add_css_class("primary");
    row.append(&save);
    col.append(&row);
    let close = sheet::present(app, Some("Reading Plan"), &col, 440);
    *close_cell.borrow_mut() = Some(close.clone());
    let (a, b, ex) = (app.clone(), book.clone(), existing);
    save.connect_clicked(move |_| {
        let t = *picked.borrow();
        if t < today_n {
            return;
        }
        let plan = ReadingPlan {
            book_id: b.id.clone(),
            target_date: dates::key(t),
            // Re-saving keeps the original anchor so "behind" stays honest.
            start_date: ex
                .as_ref()
                .map(|p| p.start_date.clone())
                .unwrap_or_else(|| today_key.clone()),
            start_progress: ex.as_ref().map(|p| p.start_progress).unwrap_or(b.progress),
            ..Default::default()
        };
        a.update(|d| {
            d.plans.retain(|p| p.book_id != plan.book_id);
            d.plans.push(plan.clone());
        });
        a.toast("Plan saved");
        close();
    });
}

/// Collections with a check for those holding every one of `ids`; clicking toggles membership.
pub fn add_to_collection_sheet(app: &AppRc, ids: Vec<String>) {
    let a = app.clone();
    sheet::present_live(app, Some("Collections"), 420, move |content, _close| {
        let col = vbox(4);
        col.set_margin_start(12);
        col.set_margin_end(12);
        col.set_margin_bottom(16);
        let d = a.data();
        for c in &d.collections {
            let has = ids.iter().all(|i| c.book_ids.contains(i));
            let row = gtk::Button::new();
            row.add_css_class("menu-row");
            let h = hbox(14);
            h.append(&icon("collections-bookmark", 22));
            let l = label(&c.name, &[]);
            l.set_hexpand(true);
            h.append(&l);
            h.append(&label(
                &plural(c.book_ids.len(), "book"),
                &["text2", "caption"],
            ));
            let ch = icon(
                if has {
                    "check-circle"
                } else {
                    "radio-button-unchecked"
                },
                22,
            );
            if has {
                ch.add_css_class("accent");
            }
            h.append(&ch);
            row.set_child(Some(&h));
            let (a2, cid, ids2) = (a.clone(), c.id.clone(), ids.clone());
            row.connect_clicked(move |_| {
                a2.update(|d| {
                    if let Some(c) = d.collections.iter_mut().find(|c| c.id == cid) {
                        if ids2.iter().all(|i| c.book_ids.contains(i)) {
                            c.book_ids.retain(|b| !ids2.contains(b));
                        } else {
                            for i in &ids2 {
                                if !c.book_ids.contains(i) {
                                    c.book_ids.push(i.clone());
                                }
                            }
                        }
                    }
                })
            });
            col.append(&row);
        }
        drop(d);
        let new = gtk::Button::new();
        new.add_css_class("menu-row");
        let h = hbox(14);
        h.append(&icon("add", 22));
        h.append(&label("New Collection", &["accent", "semibold"]));
        new.set_child(Some(&h));
        let (a2, ids2) = (a.clone(), ids.clone());
        new.connect_clicked(move |_| {
            let (a3, ids3) = (a2.clone(), ids2.clone());
            name_dialog(&a2, "New Collection", "", "Create", move |name| {
                a3.update(|d| d.create_collection(&name, ids3.clone()));
            });
        });
        col.append(&new);
        content.append(&col);
    });
}

/// A collection's books, with rename and delete.
pub fn collection_sheet(app: &AppRc, id: &str) {
    let (a, id) = (app.clone(), id.to_string());
    let name = app
        .data()
        .collections
        .iter()
        .find(|c| c.id == id)
        .map(|c| c.name.clone())
        .unwrap_or_default();
    sheet::present_live(app, Some(&name), 640, move |content, close| {
        let d = a.data();
        let Some(c) = d.collections.iter().find(|c| c.id == id).cloned() else {
            let cl = close.clone();
            glib::idle_add_local_once(move || cl());
            return;
        };
        let books: Vec<Book> = c
            .book_ids
            .iter()
            .filter_map(|b| d.book(b).cloned())
            .collect();
        let others: Vec<Book> = d
            .books
            .iter()
            .filter(|b| !c.book_ids.contains(&b.id))
            .cloned()
            .collect();
        drop(d);
        let col = vbox(14);
        col.set_margin_start(24);
        col.set_margin_end(24);
        col.set_margin_bottom(24);
        let actions = hbox(8);
        let rename = gtk::Button::with_label("Rename");
        rename.add_css_class("chip");
        let (a2, cid, cname) = (a.clone(), c.id.clone(), c.name.clone());
        rename.connect_clicked(move |_| {
            let (a3, cid2) = (a2.clone(), cid.clone());
            name_dialog(&a2, "Rename Collection", &cname, "Rename", move |n| {
                a3.update(|d| {
                    if let Some(c) = d.collections.iter_mut().find(|c| c.id == cid2) {
                        c.name = n.trim().to_string();
                    }
                })
            });
        });
        let del = gtk::Button::with_label("Delete Collection");
        del.add_css_class("chip");
        del.add_css_class("destructive");
        let (a2, cid, cl) = (a.clone(), c.id.clone(), close.clone());
        del.connect_clicked(move |_| {
            cl();
            a2.update(|d| d.collections.retain(|c| c.id != cid));
        });
        actions.append(&label(&plural(books.len(), "book"), &["secondary"]));
        actions.append(&spacer());
        actions.append(&rename);
        actions.append(&del);
        col.append(&actions);
        if books.is_empty() {
            col.append(&label("No books yet. Add some from below.", &["secondary"]));
        }
        let flow = gtk::FlowBox::new();
        flow.set_selection_mode(gtk::SelectionMode::None);
        flow.set_max_children_per_line(5);
        flow.set_min_children_per_line(3);
        flow.set_column_spacing(16);
        flow.set_row_spacing(16);
        for b in &books {
            let v = vbox(6);
            let btn = gtk::Button::new();
            btn.add_css_class("plain");
            btn.set_child(Some(&Cover::new(&a, b, 96)));
            btn.set_tooltip_text(Some(&b.title));
            let (a2, bid, cl) = (a.clone(), b.id.clone(), close.clone());
            btn.connect_clicked(move |_| {
                cl();
                a2.open(&bid, None);
            });
            v.append(&btn);
            let rm = gtk::Button::with_label("Remove");
            rm.add_css_class("flat");
            rm.add_css_class("caption");
            let (a2, cid, bid) = (a.clone(), c.id.clone(), b.id.clone());
            rm.connect_clicked(move |_| {
                a2.update(|d| {
                    if let Some(c) = d.collections.iter_mut().find(|c| c.id == cid) {
                        c.book_ids.retain(|x| *x != bid);
                    }
                })
            });
            v.append(&rm);
            flow.insert(&v, -1);
        }
        col.append(&flow);
        if !others.is_empty() {
            col.append(&label("Add books", &["eyebrow"]));
            for b in others {
                let row = gtk::Button::new();
                row.add_css_class("menu-row");
                let r = book_row(&a, &b, &b.author);
                let plus = icon("add", 22);
                plus.add_css_class("accent");
                r.append(&plus);
                row.set_child(Some(&r));
                let (a2, cid, bid) = (a.clone(), c.id.clone(), b.id.clone());
                row.connect_clicked(move |_| {
                    a2.update(|d| {
                        if let Some(c) = d.collections.iter_mut().find(|c| c.id == cid) {
                            c.book_ids.push(bid.clone());
                        }
                    })
                });
                col.append(&row);
            }
        }
        content.append(&col);
    });
}

/// Asks for a name; `on_ok` gets it when confirmed and non-empty.
pub fn name_dialog(
    app: &AppRc,
    title: &str,
    initial: &str,
    action: &str,
    on_ok: impl Fn(String) + 'static,
) {
    let col = vbox(16);
    col.set_margin_start(24);
    col.set_margin_end(24);
    col.set_margin_bottom(24);
    let entry = gtk::Entry::new();
    entry.set_text(initial);
    entry.set_placeholder_text(Some("Name"));
    col.append(&entry);
    let ok = gtk::Button::with_label(action);
    ok.add_css_class("primary");
    ok.set_halign(gtk::Align::End);
    col.append(&ok);
    let close = sheet::present(app, Some(title), &col, 400);
    let on_ok = Rc::new(on_ok);
    let submit = {
        let (entry, close, on_ok) = (entry.clone(), close.clone(), on_ok.clone());
        Rc::new(move || {
            let t = entry.text().trim().to_string();
            if !t.is_empty() {
                close();
                on_ok(t);
            }
        })
    };
    let s = submit.clone();
    ok.connect_clicked(move |_| s());
    entry.connect_activate(move |_| submit());
    let e = entry.clone();
    glib::idle_add_local_once(move || {
        e.grab_focus();
    });
}
