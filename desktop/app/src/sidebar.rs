//! The sidebar shown on wide windows, after Apple Books on the Mac: search, Home and Feed, the library's views,
//! every collection, and the profile and settings at the bottom. Narrow windows use the phone's pill bar instead.

use std::rc::Rc;

use gbook_core::library::plural;
use gtk::prelude::*;

use crate::library::View;
use crate::shell::{ShellRc, Tab};
use crate::state::AppRc;
use crate::widgets::*;

pub const WIDTH: i32 = 236;

pub fn build(app: &AppRc, shell: &ShellRc) -> gtk::Widget {
    let root = vbox(0);
    root.add_css_class("sidebar");
    root.set_size_request(WIDTH, -1);
    root.set_hexpand(false);
    // The top strip holds the window buttons and drags the window, like a title bar.
    let handle = gtk::WindowHandle::new();
    let top = vbox(0);
    top.set_size_request(-1, 58);
    handle.set_child(Some(&top));
    root.append(&handle);

    let search = gtk::SearchEntry::new();
    search.add_css_class("side-search");
    search.set_placeholder_text(Some("Search"));
    search.set_margin_start(12);
    search.set_margin_end(12);
    search.set_margin_bottom(6);
    let s = shell.clone();
    search.connect_search_changed(move |e| {
        let q = e.text().to_string();
        if !q.is_empty() || s.current() == Tab::Search {
            s.search_for(&q);
        }
    });
    let s = shell.clone();
    search.connect_activate(move |e| s.search_for(&e.text()));
    root.append(&search);

    let list = vbox(1);
    list.set_margin_start(10);
    list.set_margin_end(10);
    let scroller = gtk::ScrolledWindow::new();
    scroller.set_hscrollbar_policy(gtk::PolicyType::Never);
    scroller.set_vexpand(true);
    scroller.set_child(Some(&list));
    root.append(&scroller);

    let bottom = vbox(1);
    bottom.add_css_class("side-bottom");
    bottom.set_margin_start(10);
    bottom.set_margin_end(10);
    bottom.set_margin_bottom(12);
    root.append(&bottom);

    let render = {
        let (app, shell, list, bottom) = (app.clone(), shell.clone(), list.clone(), bottom.clone());
        Rc::new(move || fill(&app, &shell, &list, &bottom)) as Rc<dyn Fn()>
    };
    render();
    let r = render.clone();
    app.on_change(move || r());
    *shell.sidebar_render.borrow_mut() = Some(render);
    root.upcast()
}

/// A sidebar entry: an accent icon, a name and an optional count; `on` marks the page being shown.
fn item(
    ic: &str,
    name: &str,
    count: Option<usize>,
    on: bool,
    f: impl Fn() + 'static,
) -> gtk::Button {
    let b = gtk::Button::new();
    b.add_css_class("side-item");
    if on {
        b.add_css_class("on");
    }
    let h = hbox(10);
    h.append(&icon(ic, 19));
    let l = label(name, &[]);
    l.set_hexpand(true);
    l.set_ellipsize(gtk::pango::EllipsizeMode::End);
    h.append(&l);
    if let Some(n) = count {
        h.append(&label(&n.to_string(), &["count"]));
    }
    b.set_child(Some(&h));
    b.connect_clicked(move |_| f());
    b
}

fn section(title: &str) -> gtk::Label {
    label(title, &["side-section"])
}

fn fill(app: &AppRc, shell: &ShellRc, list: &gtk::Box, bottom: &gtk::Box) {
    clear(list);
    clear(bottom);
    let d = app.data();
    let tab = shell.current();
    let view = shell.lib_view.get();
    let coll = shell.lib_collection.borrow().clone();
    let at = |v: View| tab == Tab::Library && view == v;
    let s = shell.clone();
    list.append(&item(
        "dashboard",
        "Home",
        None,
        at(View::Overview),
        move || s.show_library(View::Overview),
    ));
    let s = shell.clone();
    list.append(&item(
        "dynamic-feed",
        "Feed",
        Some(d.posts.len()).filter(|n| *n > 0),
        tab == Tab::Feed,
        move || s.select(Tab::Feed),
    ));

    list.append(&section("Library"));
    let s = shell.clone();
    list.append(&item(
        "local-library",
        "All Books",
        Some(d.books.len()),
        at(View::Books),
        move || s.show_library(View::Books),
    ));
    let pdfs = d
        .books
        .iter()
        .filter(|b| b.format == gbook_core::BookFormat::PDF)
        .count();
    if pdfs > 0 {
        let s = shell.clone();
        list.append(&item(
            "description",
            "PDFs",
            Some(pdfs),
            at(View::Pdfs),
            move || s.show_library(View::Pdfs),
        ));
    }
    let finished = d.books.iter().filter(|b| b.progress >= 0.995).count();
    if finished > 0 {
        let s = shell.clone();
        list.append(&item(
            "check-circle",
            "Finished",
            Some(finished),
            at(View::Finished),
            move || s.show_library(View::Finished),
        ));
    }
    let s = shell.clone();
    list.append(&item(
        "format-quote",
        "Highlights",
        Some(d.highlights.len()),
        at(View::Highlights),
        move || s.show_library(View::Highlights),
    ));
    let s = shell.clone();
    list.append(&item(
        "event-available",
        "Plans",
        Some(d.plans.len()),
        at(View::Plans),
        move || s.show_library(View::Plans),
    ));

    list.append(&section("My Collections"));
    for c in &d.collections {
        let s = shell.clone();
        let id = c.id.clone();
        list.append(&item(
            "collections-bookmark",
            &c.name,
            Some(c.book_ids.len()),
            at(View::Collection) && coll == c.id,
            move || s.show_collection(&id),
        ));
    }
    let (a, s) = (app.clone(), shell.clone());
    let new = item("add", "New Collection", None, false, move || {
        let (a2, s2) = (a.clone(), s.clone());
        crate::booksheet::name_dialog(&a, "New Collection", "", "Create", move |name| {
            let id = a2.update(|d| d.create_collection(&name, Vec::new()));
            s2.show_collection(&id);
        });
    });
    new.add_css_class("dim");
    list.append(&new);

    // Profile and settings stay at the bottom.
    let name = if d.settings.profile_name.trim().is_empty() {
        "Your profile".to_string()
    } else {
        d.settings.profile_name.clone()
    };
    let books = plural(d.books.len(), "book");
    drop(d);
    let profile = gtk::Button::new();
    profile.add_css_class("side-item");
    let h = hbox(10);
    h.append(&avatar(app, 30));
    let v = vbox(0);
    v.set_valign(gtk::Align::Center);
    let n = label(&name, &["semibold"]);
    n.set_ellipsize(gtk::pango::EllipsizeMode::End);
    v.append(&n);
    v.append(&label(&books, &["small", "text2"]));
    h.append(&v);
    profile.set_child(Some(&h));
    let s = shell.clone();
    profile.connect_clicked(move |_| s.open_settings("profile"));
    bottom.append(&profile);
    let s = shell.clone();
    bottom.append(&item(
        "settings",
        "Settings",
        None,
        tab == Tab::Settings,
        move || s.select(Tab::Settings),
    ));
}
