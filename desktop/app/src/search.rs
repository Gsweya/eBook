//! Search across the library: books and authors, collections, highlights and notes, and the text of every
//! book (scanned on a worker thread, so typing stays smooth).

use std::cell::Cell;
use std::rc::Rc;

use gbook_core::library::plural;
use gbook_core::{Book, IMAGE, store};
use gtk::prelude::*;
use gtk::{gio, glib};

use crate::library::{book_row, highlight_quote};
use crate::shell::{self, ShellRc};
use crate::state::AppRc;
use crate::widgets::*;

pub fn page(app: &AppRc, shell: &ShellRc) -> gtk::Widget {
    let content = vbox(16);
    content.set_margin_top(76);
    content.set_margin_bottom(130);
    content.set_margin_start(24);
    content.set_margin_end(24);
    content.append(&label("Search", &["large-title"]));
    let entry = gtk::SearchEntry::new();
    entry.set_placeholder_text(Some("Books, authors, collections, highlights"));
    content.append(&entry);
    let results = vbox(14);
    content.append(&results);
    let clamp = Clamp::new(&content, 720);
    let top = top_bar(&hbox(0), None, &hbox(0), false, None);
    let e = entry.clone();
    *shell.search_set.borrow_mut() = Some(Rc::new(move |q: &str| {
        if e.text() != q {
            e.set_text(q);
            e.set_position(-1);
        }
    }));
    let e = entry.clone();
    *shell.search_focus.borrow_mut() = Some(Rc::new(move || {
        let e = e.clone();
        glib::idle_add_local_once(move || {
            e.grab_focus();
        });
    }));
    // Each query gets a generation; full-text results from an older query are dropped.
    let generation = Rc::new(Cell::new(0u32));
    let render = {
        let (app, shell, entry, results, generation) = (
            app.clone(),
            shell.clone(),
            entry.clone(),
            results.clone(),
            generation.clone(),
        );
        Rc::new(move || {
            generation.set(generation.get() + 1);
            fill(
                &app,
                &shell,
                &results,
                entry.text().trim(),
                generation.clone(),
            );
        }) as Rc<dyn Fn()>
    };
    let r = render.clone();
    entry.connect_search_changed(move |_| r());
    shell::scrolled_page(app, &top, &clamp.upcast(), render)
}

fn section(title: &str) -> gtk::Label {
    let l = label(title, &["section-title"]);
    l.set_margin_top(8);
    l
}

fn fill(app: &AppRc, shell: &ShellRc, results: &gtk::Box, q: &str, generation: Rc<Cell<u32>>) {
    clear(results);
    let d = app.data();
    if q.is_empty() {
        // Recently opened books, to get back to one quickly.
        let recent: Vec<Book> = d
            .books_by_recent()
            .into_iter()
            .filter(|b| b.last_opened_at > 0)
            .take(6)
            .cloned()
            .collect();
        drop(d);
        if !recent.is_empty() {
            results.append(&section("Recently Read"));
            for b in recent {
                results.append(&book_button(app, &b, &b.author));
            }
        }
        return;
    }
    let t = q.to_lowercase();
    let books: Vec<Book> = d
        .books
        .iter()
        .filter(|b| b.title.to_lowercase().contains(&t) || b.author.to_lowercase().contains(&t))
        .cloned()
        .collect();
    let cols: Vec<(String, String, usize)> = d
        .collections
        .iter()
        .filter(|c| c.name.to_lowercase().contains(&t))
        .map(|c| (c.id.clone(), c.name.clone(), c.book_ids.len()))
        .collect();
    let hls: Vec<(gbook_core::Highlight, Book)> = d
        .highlights
        .iter()
        .filter(|h| {
            h.text.to_lowercase().contains(&t)
                || h.note
                    .as_ref()
                    .is_some_and(|n| n.to_lowercase().contains(&t))
        })
        .filter_map(|h| d.book(&h.book_id).map(|b| (h.clone(), b.clone())))
        .take(30)
        .collect();
    let all_books: Vec<Book> = d.books.iter().filter(|b| b.text_ready).cloned().collect();
    drop(d);
    if !books.is_empty() {
        results.append(&section("Books"));
        for b in &books {
            results.append(&book_button(app, b, &b.author));
        }
    }
    if !cols.is_empty() {
        results.append(&section("Collections"));
        for (id, name, n) in cols {
            let btn = gtk::Button::new();
            btn.add_css_class("menu-row");
            let h = hbox(14);
            h.append(&icon("collections-bookmark", 22));
            let l = label(&name, &["semibold"]);
            l.set_hexpand(true);
            h.append(&l);
            h.append(&label(&plural(n, "book"), &["caption", "text2"]));
            btn.set_child(Some(&h));
            let s = shell.clone();
            btn.connect_clicked(move |_| s.show_collection(&id));
            results.append(&btn);
        }
    }
    if !hls.is_empty() {
        results.append(&section("Highlights"));
        for (h, b) in &hls {
            results.append(&highlight_quote(app, b, h));
        }
    }
    // Full text, a little after typing pauses.
    if t.chars().count() < 3 {
        if results.first_child().is_none() {
            results.append(&label(
                "Keep typing to search inside your books…",
                &["secondary"],
            ));
        }
        return;
    }
    let inside = vbox(8);
    results.append(&inside);
    let mine = generation.get();
    let (app, paths) = (app.clone(), app.paths.clone());
    glib::spawn_future_local(async move {
        glib::timeout_future(std::time::Duration::from_millis(250)).await;
        if generation.get() != mine {
            return;
        }
        let term = t.clone();
        let hits = gio::spawn_blocking(move || {
            let mut out = Vec::new();
            for b in all_books {
                let Some(c) = store::load_content(&paths, &b.id) else {
                    continue;
                };
                let mut n = 0;
                let mut first = None;
                for (ci, ch) in c.chapters.iter().enumerate() {
                    for (bi, bl) in ch.blocks.iter().enumerate() {
                        if bl.kind == IMAGE {
                            continue;
                        }
                        let lower = bl.text.to_lowercase();
                        if let Some(at) = lower.find(&term) {
                            n += lower.matches(&term).count();
                            if first.is_none() {
                                let pre = lower[..at].chars().count().saturating_sub(40);
                                let snip: String = bl.text.chars().skip(pre).take(140).collect();
                                first = Some((
                                    ci as i32,
                                    bi as i32,
                                    ch.title.clone(),
                                    format!("{}{snip}…", if pre > 0 { "…" } else { "" }),
                                ));
                            }
                        }
                    }
                }
                if let Some(f) = first {
                    out.push((b, n, f));
                }
            }
            out
        })
        .await
        .unwrap_or_default();
        if generation.get() != mine {
            return;
        }
        if !hits.is_empty() {
            inside.append(&section("In Your Books"));
        }
        for (b, n, (ci, bi, chapter, snippet)) in hits {
            let btn = gtk::Button::new();
            btn.add_css_class("menu-row");
            let v = vbox(6);
            v.append(&book_row(
                &app,
                &b,
                &format!("{} · first in {chapter}", plural(n, "match")),
            ));
            v.append(&wrap_label(&snippet, &["quote", "text2"], 3));
            btn.set_child(Some(&v));
            let (a, id) = (app.clone(), b.id.clone());
            btn.connect_clicked(move |_| a.open(&id, Some((ci, bi))));
            inside.append(&btn);
        }
        if inside.first_child().is_none()
            && inside
                .parent()
                .and_downcast::<gtk::Box>()
                .is_some_and(|p| p.first_child().as_ref() == Some(inside.upcast_ref()))
        {
            inside.append(&crate::library::empty_state(
                "No results",
                &format!("Nothing matches “{t}”."),
                None,
            ));
        }
    });
}

fn book_button(app: &AppRc, b: &Book, detail: &str) -> gtk::Widget {
    let btn = gtk::Button::new();
    btn.add_css_class("menu-row");
    btn.set_child(Some(&book_row(app, b, detail)));
    let (a, id) = (app.clone(), b.id.clone());
    btn.connect_clicked(move |_| a.open(&id, None));
    btn.upcast()
}
