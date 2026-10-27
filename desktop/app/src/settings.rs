//! Settings, grouped as on the phone: Profile; Reading and Goals; Appearance; Backup; About. Each row opens
//! its own page within the tab.

use std::cell::RefCell;
use std::rc::Rc;

use gbook_core::backup;
use gtk::prelude::*;
use gtk::{gdk_pixbuf, gio, glib};

use crate::reader_options;
use crate::shell::{self, ShellRc};
use crate::state::AppRc;
use crate::theme::{PALETTES, READER_THEMES, READING_FONTS};
use crate::widgets::*;

pub fn page(app: &AppRc, shell: &ShellRc) -> gtk::Widget {
    let current: Rc<RefCell<Option<String>>> = Rc::default();
    let content = vbox(0);
    content.set_margin_top(70);
    content.set_margin_bottom(130);
    content.set_margin_start(24);
    content.set_margin_end(24);
    let clamp = Clamp::new(&content, 680);
    let back = circle_button("arrow-back-ios-new", "Back");
    back.set_visible(false);
    let start = hbox(0);
    start.append(&back);
    let top = top_bar(&start, None, &hbox(0), false, Some((680, 24)));
    let render: Rc<RefCell<Option<Rc<dyn Fn()>>>> = Rc::default();
    let r = {
        let (app, content, current, render, back) = (
            app.clone(),
            content.clone(),
            current.clone(),
            render.clone(),
            back.clone(),
        );
        Rc::new(move || {
            clear(&content);
            let again = render.borrow().clone().unwrap();
            let page = current.borrow().clone();
            back.set_visible(page.is_some());
            let nav = {
                let (current, again) = (current.clone(), again.clone());
                Rc::new(move |p: Option<&str>| {
                    *current.borrow_mut() = p.map(String::from);
                    again();
                }) as Rc<dyn Fn(Option<&str>)>
            };
            match page.as_deref() {
                None => main(&app, &content, nav),
                Some(p) => sub_page(&app, &content, p),
            }
        }) as Rc<dyn Fn()>
    };
    *render.borrow_mut() = Some(r.clone());
    let (c, rr) = (current.clone(), r.clone());
    *shell.settings_nav.borrow_mut() = Some(Rc::new(move |p| {
        *c.borrow_mut() = p.map(String::from);
        rr();
    }));
    let (c, rr) = (current.clone(), r.clone());
    back.connect_clicked(move |_| {
        *c.borrow_mut() = None;
        rr();
    });
    shell::scrolled_page(app, &top, &clamp.upcast(), r)
}

const PAGES: [(&str, &str, &str, &str); 6] = [
    (
        "reading",
        "menu-book",
        "Reading",
        "Font, size, margins, theme, page turning",
    ),
    (
        "goals",
        "notifications-active",
        "Goals",
        "Daily reading goal",
    ),
    (
        "appearance",
        "palette",
        "Appearance",
        "Mode, accent colour, pure black",
    ),
    (
        "backup",
        "cloud",
        "Backup",
        "Back up, restore, your data folder",
    ),
    ("about", "info", "About", "Version and credits"),
    ("profile", "person", "Profile", "Name and profile picture"),
];

fn page_title(id: &str) -> &'static str {
    PAGES
        .iter()
        .find(|p| p.0 == id)
        .map(|p| p.2)
        .unwrap_or("Settings")
}

fn group(rows: Vec<gtk::Widget>) -> gtk::Box {
    let g = vbox(0);
    g.add_css_class("group");
    g.set_overflow(gtk::Overflow::Hidden);
    for (i, r) in rows.into_iter().enumerate() {
        if i > 0 {
            let sep = vbox(0);
            sep.add_css_class("row-sep");
            g.append(&sep);
        }
        g.append(&r);
    }
    g
}

fn group_title(t: &str) -> gtk::Label {
    label(&t.to_uppercase(), &["group-title"])
}

/// A settings row: icon tile, title and subtitle, a value on the right.
fn nav_row(
    ic: Option<&str>,
    title: &str,
    subtitle: &str,
    value: Option<&str>,
    f: impl Fn() + 'static,
) -> gtk::Widget {
    let b = gtk::Button::new();
    b.add_css_class("row");
    let h = hbox(14);
    if let Some(ic) = ic {
        let tile = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        tile.add_css_class("icon-tile");
        tile.set_valign(gtk::Align::Center);
        tile.set_hexpand(false);
        let i = icon(ic, 21);
        i.set_hexpand(true);
        tile.append(&i);
        h.append(&tile);
    }
    let v = vbox(1);
    v.set_hexpand(true);
    v.set_valign(gtk::Align::Center);
    v.append(&label(title, &[]));
    if !subtitle.is_empty() {
        v.append(&wrap_label(subtitle, &["caption", "text2"], 2));
    }
    h.append(&v);
    if let Some(val) = value {
        let l = label(val, &["text2"]);
        l.set_valign(gtk::Align::Center);
        h.append(&l);
    }
    b.set_child(Some(&h));
    b.connect_clicked(move |_| f());
    b.upcast()
}

fn switch_row(title: &str, subtitle: &str, on: bool, f: impl Fn(bool) + 'static) -> gtk::Widget {
    let h = hbox(14);
    h.add_css_class("row");
    let v = vbox(1);
    v.set_hexpand(true);
    v.append(&label(title, &[]));
    if !subtitle.is_empty() {
        v.append(&wrap_label(subtitle, &["caption", "text2"], 2));
    }
    h.append(&v);
    let sw = gtk::Switch::new();
    sw.set_active(on);
    sw.set_valign(gtk::Align::Center);
    sw.connect_active_notify(move |s| f(s.is_active()));
    h.append(&sw);
    h.upcast()
}

fn main(app: &AppRc, content: &gtk::Box, nav: Rc<dyn Fn(Option<&str>)>) {
    let s = app.data().settings.clone();
    content.append(&label("Settings", &["large-title"]));
    content.append(&group_title("Profile"));
    let profile = gtk::Button::new();
    profile.add_css_class("row");
    let h = hbox(14);
    h.append(&avatar(app, 46));
    let v = vbox(1);
    v.set_valign(gtk::Align::Center);
    v.append(&label(
        if s.profile_name.trim().is_empty() {
            "Your profile"
        } else {
            &s.profile_name
        },
        &[],
    ));
    v.append(&label("Name and profile picture", &["caption", "text2"]));
    h.append(&v);
    profile.set_child(Some(&h));
    let n = nav.clone();
    profile.connect_clicked(move |_| n(Some("profile")));
    content.append(&group(vec![profile.upcast()]));
    let row = |id: &'static str, value: Option<String>| {
        let p = PAGES.iter().find(|p| p.0 == id).unwrap();
        let n = nav.clone();
        nav_row(Some(p.1), p.2, p.3, value.as_deref(), move || n(Some(id)))
    };
    content.append(&group_title("Reading"));
    content.append(&group(vec![
        row(
            "reading",
            Some(if s.page_mode == 1 {
                "Pages".into()
            } else {
                "Scroll".into()
            }),
        ),
        row(
            "goals",
            (s.daily_goal_min > 0).then(|| format!("{} min", s.daily_goal_min)),
        ),
    ]));
    content.append(&group_title("Personalise"));
    content.append(&group(vec![row(
        "appearance",
        Some(
            ["System", "Light", "Dark"]
                .get(s.appearance as usize)
                .copied()
                .unwrap_or("System")
                .to_string(),
        ),
    )]));
    content.append(&group_title("Library"));
    content.append(&group(vec![row("backup", None)]));
    content.append(&group_title("About"));
    content.append(&group(vec![row(
        "about",
        Some(env!("CARGO_PKG_VERSION").to_string()),
    )]));
}

fn sub_page(app: &AppRc, content: &gtk::Box, page: &str) {
    content.append(&label(page_title(page), &["large-title"]));
    match page {
        "profile" => profile(app, content),
        "reading" => reading(app, content),
        "goals" => goals(app, content),
        "appearance" => {
            content.append(&group_title("Theme"));
            let card = vbox(0);
            card.add_css_class("group");
            let inner = appearance_controls(app);
            inner.set_margin_top(16);
            inner.set_margin_bottom(16);
            inner.set_margin_start(16);
            inner.set_margin_end(16);
            card.append(&inner);
            content.append(&card);
        }
        "backup" => backup_page(app, content),
        _ => about(app, content),
    }
}

/// Light/dark/system, the accent palette and pure black: shared by the Appearance page and the Library's theme button.
pub fn appearance_controls(app: &AppRc) -> gtk::Box {
    let s = app.data().settings.clone();
    let col = vbox(16);
    let seg = hbox(0);
    seg.add_css_class("segment");
    seg.set_homogeneous(true);
    for (i, name) in ["System", "Light", "Dark"].iter().enumerate() {
        let b = gtk::Button::with_label(name);
        if s.appearance == i as i32 {
            b.add_css_class("on");
            b.add_css_class("accent");
        }
        let a = app.clone();
        b.connect_clicked(move |_| a.update(|d| d.settings.appearance = i as i32));
        seg.append(&b);
    }
    col.append(&seg);
    col.append(&label("ACCENT", &["eyebrow"]));
    let flow = gtk::FlowBox::new();
    flow.set_selection_mode(gtk::SelectionMode::None);
    flow.set_max_children_per_line(6);
    flow.set_min_children_per_line(4);
    flow.set_column_spacing(12);
    flow.set_row_spacing(12);
    for p in PALETTES {
        let v = vbox(6);
        let b = gtk::Button::new();
        b.add_css_class("swatch");
        if s.accent == p.id {
            b.add_css_class("on");
        }
        b.set_halign(gtk::Align::Center);
        let colors = p.gradient.to_vec();
        let da = gtk::DrawingArea::new();
        da.set_content_width(44);
        da.set_content_height(44);
        da.set_draw_func(move |_, cr, w, h| {
            let g = gtk::cairo::LinearGradient::new(0.0, 0.0, w as f64, h as f64);
            for (i, c) in colors.iter().enumerate() {
                g.add_color_stop_rgb(
                    i as f64 / (colors.len() - 1).max(1) as f64,
                    c.0 as f64 / 255.0,
                    c.1 as f64 / 255.0,
                    c.2 as f64 / 255.0,
                );
            }
            cr.arc(
                w as f64 / 2.0,
                h as f64 / 2.0,
                w.min(h) as f64 / 2.0,
                0.0,
                std::f64::consts::TAU,
            );
            let _ = cr.set_source(&g);
            let _ = cr.fill();
        });
        b.set_child(Some(&da));
        b.set_tooltip_text(Some(p.name));
        let (a, id) = (app.clone(), p.id);
        b.connect_clicked(move |_| a.update(|d| d.settings.accent = id.to_string()));
        v.append(&b);
        let l = label(p.name, &["small", "text2"]);
        l.set_xalign(0.5);
        v.append(&l);
        flow.insert(&v, -1);
    }
    col.append(&flow);
    let a = app.clone();
    let row = switch_row(
        "Pure black",
        "Black backgrounds in dark mode",
        s.amoled,
        move |on| a.update(|d| d.settings.amoled = on),
    );
    row.remove_css_class("row");
    col.append(&row);
    col
}

fn profile(app: &AppRc, content: &gtk::Box) {
    let s = app.data().settings.clone();
    let col = vbox(16);
    col.set_margin_top(20);
    let av = avatar(app, 112);
    av.set_halign(gtk::Align::Center);
    col.append(&av);
    let row = hbox(10);
    row.set_halign(gtk::Align::Center);
    let pick = gtk::Button::with_label("Choose Picture…");
    pick.add_css_class("pill");
    let a = app.clone();
    pick.connect_clicked(move |_| choose_avatar(&a));
    row.append(&pick);
    if s.avatar_version != 0 {
        let rm = gtk::Button::with_label("Remove");
        rm.add_css_class("pill");
        let a = app.clone();
        rm.connect_clicked(move |_| {
            let _ = std::fs::remove_file(a.paths.avatar());
            a.update(|d| d.settings.avatar_version = 0);
        });
        row.append(&rm);
    }
    col.append(&row);
    col.append(&group_title("Name"));
    let entry = gtk::Entry::new();
    entry.set_text(&s.profile_name);
    entry.set_placeholder_text(Some("Your name"));
    let a = app.clone();
    let pending: Rc<RefCell<Option<glib::SourceId>>> = Rc::default();
    entry.connect_changed(move |e| {
        if let Some(t) = pending.borrow_mut().take() {
            t.remove();
        }
        let (a2, name, p2) = (a.clone(), e.text().to_string(), pending.clone());
        // Saved once typing pauses, so the page doesn't rebuild under the cursor.
        *pending.borrow_mut() = Some(glib::timeout_add_local_once(
            std::time::Duration::from_millis(700),
            move || {
                p2.borrow_mut().take();
                a2.update(|d| d.settings.profile_name = name.trim().to_string());
            },
        ));
    });
    col.append(&entry);
    content.append(&col);
}

/// Picks an image, crops the middle square and saves it as the 512px profile picture.
fn choose_avatar(app: &AppRc) {
    let filter = gtk::FileFilter::new();
    filter.set_name(Some("Images"));
    filter.add_pixbuf_formats();
    let filters = gio::ListStore::new::<gtk::FileFilter>();
    filters.append(&filter);
    let dialog = gtk::FileDialog::builder()
        .title("Profile Picture")
        .filters(&filters)
        .modal(true)
        .build();
    let a = app.clone();
    let win = app.window();
    dialog.open(win.as_ref(), gio::Cancellable::NONE, move |res| {
        let Some(path) = res.ok().and_then(|f| f.path()) else {
            return;
        };
        let saved = (|| -> Option<()> {
            let p = gdk_pixbuf::Pixbuf::from_file(&path).ok()?;
            let p = p.apply_embedded_orientation().unwrap_or(p);
            let side = p.width().min(p.height());
            let sq = p.new_subpixbuf((p.width() - side) / 2, (p.height() - side) / 2, side, side);
            let out = sq.scale_simple(512, 512, gdk_pixbuf::InterpType::Bilinear)?;
            out.savev(a.paths.avatar(), "jpeg", &[("quality", "90")])
                .ok()
        })();
        match saved {
            Some(()) => {
                a.forget_textures(&a.paths.avatar());
                a.update(|d| d.settings.avatar_version = gbook_core::now_ms());
            }
            None => a.toast("Couldn't use that picture"),
        }
    });
}

fn reading(app: &AppRc, content: &gtk::Box) {
    let s = app.data().settings.clone();
    content.append(&group_title("Font"));
    let fonts = gtk::FlowBox::new();
    fonts.set_selection_mode(gtk::SelectionMode::None);
    fonts.set_max_children_per_line(2);
    fonts.set_min_children_per_line(2);
    fonts.set_column_spacing(10);
    fonts.set_row_spacing(10);
    fonts.set_homogeneous(true);
    for f in READING_FONTS {
        let b = gtk::Button::new();
        b.add_css_class("mode-card");
        if f.id == s.font {
            b.add_css_class("on");
        }
        let v = vbox(0);
        let n = gtk::Label::new(Some(f.name));
        n.set_xalign(0.0);
        n.set_attributes(Some(&{
            let a = gtk::pango::AttrList::new();
            a.insert(gtk::pango::AttrFontDesc::new(
                &gtk::pango::FontDescription::from_string(&format!("{} 15", f.family)),
            ));
            a
        }));
        v.append(&n);
        v.append(&label(&format!("like {}", f.like), &["caption", "text2"]));
        b.set_child(Some(&v));
        let (a, id) = (app.clone(), f.id);
        b.connect_clicked(move |_| a.update(|d| d.settings.font = id.to_string()));
        fonts.insert(&b, -1);
    }
    content.append(&fonts);
    content.append(&group_title("Text"));
    let card = vbox(14);
    card.add_css_class("group");
    let inner = vbox(14);
    inner.set_margin_top(16);
    inner.set_margin_bottom(16);
    inner.set_margin_start(16);
    inner.set_margin_end(16);
    let size_row = hbox(12);
    size_row.append(&label("Text size", &[]));
    size_row.append(&spacer());
    let spin = gtk::SpinButton::with_range(
        crate::reader::MIN_FONT as f64,
        crate::reader::MAX_FONT as f64,
        1.0,
    );
    spin.set_value(s.font_size as f64);
    let a = app.clone();
    spin.connect_value_changed(move |sp| {
        let v = sp.value() as f32;
        a.update(|d| d.settings.font_size = v);
    });
    size_row.append(&spin);
    inner.append(&size_row);
    for (name, on, f) in [
        (
            "Bold text",
            s.bold,
            (|d: &mut gbook_core::LibraryData, v: bool| d.settings.bold = v)
                as fn(&mut gbook_core::LibraryData, bool),
        ),
        ("Justify text", s.justify, |d, v| d.settings.justify = v),
        (
            "Post every highlight to the Feed",
            s.auto_post_highlights,
            |d, v| d.settings.auto_post_highlights = v,
        ),
    ] {
        let a = app.clone();
        let r = switch_row(name, "", on, move |v| a.update(|d| f(d, v)));
        r.remove_css_class("row");
        inner.append(&r);
    }
    card.append(&inner);
    content.append(&card);
    content.append(&group_title("Page theme"));
    let grid = gtk::Grid::new();
    grid.set_row_spacing(10);
    grid.set_column_spacing(10);
    grid.set_column_homogeneous(true);
    for (i, t) in READER_THEMES.iter().enumerate() {
        let b = gtk::Button::with_label(t.name);
        b.add_css_class("theme-tile");
        b.add_css_class(&format!("t{i}"));
        if s.theme == i as i32 {
            b.add_css_class("on");
        }
        let a = app.clone();
        b.connect_clicked(move |_| a.update(|d| d.settings.theme = i as i32));
        grid.attach(&b, (i % 3) as i32, (i / 3) as i32, 1, 1);
    }
    content.append(&grid);
    content.append(&group_title("Page turning"));
    content.append(&reader_options::page_mode(app, s.page_mode));
    content.append(&group_title("Focus tracker"));
    let card = vbox(0);
    card.add_css_class("group");
    let inner = reader_options::tracker_options(app, &s);
    inner.set_margin_top(14);
    inner.set_margin_bottom(14);
    inner.set_margin_start(16);
    inner.set_margin_end(16);
    card.append(&inner);
    content.append(&card);
    content.append(&group_title("Margins"));
    let presets = hbox(8);
    for (name, v) in [
        ("Narrow", 8),
        ("Normal", 24),
        ("Wide", 44),
        ("Extra wide", 64),
    ] {
        let b = gtk::Button::with_label(name);
        b.add_css_class("chip");
        if s.margin == v {
            b.add_css_class("on");
        }
        let a = app.clone();
        b.connect_clicked(move |_| a.update(|d| d.settings.margin = v));
        presets.append(&b);
    }
    content.append(&presets);
}

fn goals(app: &AppRc, content: &gtk::Box) {
    let goal = app.data().settings.daily_goal_min;
    content.append(&wrap_label(
        "A daily goal shows on your reading card, and GBook celebrates when you reach it. Only active reading counts: time with the reader open, the window focused, and you turning pages.",
        &["secondary"],
        4,
    ));
    content.append(&group_title("Daily goal"));
    let chips = gtk::FlowBox::new();
    chips.set_selection_mode(gtk::SelectionMode::None);
    chips.set_max_children_per_line(8);
    chips.set_column_spacing(8);
    chips.set_row_spacing(8);
    for m in [0, 10, 15, 20, 30, 45, 60, 90] {
        let b = gtk::Button::with_label(&if m == 0 {
            "Off".to_string()
        } else {
            format!("{m} min")
        });
        b.add_css_class("chip");
        b.add_css_class("solid");
        if m == goal {
            b.add_css_class("on");
        }
        let a = app.clone();
        b.connect_clicked(move |_| a.update(|d| d.settings.daily_goal_min = m));
        chips.insert(&b, -1);
    }
    content.append(&chips);
}

fn backup_page(app: &AppRc, content: &gtk::Box) {
    let last = app.data().last_backup_at;
    content.append(&wrap_label(
        "A backup is one .zip with your books, highlights, posts and reading history. It's the same format as GBook on Android: restore a phone backup here, or this one on your phone.",
        &["secondary"],
        4,
    ));
    let a1 = app.clone();
    let a2 = app.clone();
    let a3 = app.clone();
    let when = if last > 0 {
        glib::DateTime::from_unix_local(last / 1000)
            .ok()
            .and_then(|d| d.format("%-d %b %Y, %H:%M").ok())
            .map(|s| format!("Last backup {s}"))
            .unwrap_or_default()
    } else {
        "Never backed up".to_string()
    };
    content.append(&group_title("Backup"));
    content.append(&group(vec![
        nav_row(
            Some("cloud-upload"),
            "Back Up Library…",
            &when,
            None,
            move || export_backup(&a1),
        ),
        nav_row(
            Some("cloud-download"),
            "Restore from Backup…",
            "Adds what's missing; keeps everything already here",
            None,
            move || restore_backup(&a2),
        ),
        nav_row(
            Some("file-open"),
            "Open Data Folder",
            &a3.paths.root.display().to_string(),
            None,
            {
                let a = a3.clone();
                move || {
                    let uri = glib::filename_to_uri(&a.paths.root, None)
                        .map(|u| u.to_string())
                        .unwrap_or_default();
                    open_uri(&a, &uri);
                }
            },
        ),
    ]));
}

fn export_backup(app: &AppRc) {
    let (_, day) = crate::state::today();
    let dialog = gtk::FileDialog::builder()
        .title("Back Up Library")
        .initial_name(format!("GBook backup {day}.zip"))
        .modal(true)
        .build();
    let a = app.clone();
    let win = app.window();
    dialog.save(win.as_ref(), gio::Cancellable::NONE, move |res| {
        let Some(dest) = res.ok().and_then(|f| f.path()) else {
            return;
        };
        a.flush();
        let (paths, data) = (a.paths.clone(), a.data().clone());
        a.busy(Some("Backing up…"));
        let a2 = a.clone();
        glib::spawn_future_local(async move {
            let r = gio::spawn_blocking(move || {
                backup::export(&paths, &data, &dest).map_err(|e| e.to_string())
            })
            .await;
            a2.busy(None);
            match r {
                Ok(Ok(at)) => {
                    a2.update(|d| {
                        d.last_backup_at = at;
                        for b in &mut d.books {
                            b.backed_up_at = at;
                        }
                    });
                    a2.toast("Library backed up");
                }
                Ok(Err(e)) => a2.toast(&format!("Backup failed: {e}")),
                Err(_) => a2.toast("Backup failed"),
            }
        });
    });
}

fn restore_backup(app: &AppRc) {
    let filter = gtk::FileFilter::new();
    filter.set_name(Some("GBook backups (.zip)"));
    filter.add_suffix("zip");
    let filters = gio::ListStore::new::<gtk::FileFilter>();
    filters.append(&filter);
    let dialog = gtk::FileDialog::builder()
        .title("Restore from Backup")
        .filters(&filters)
        .modal(true)
        .build();
    let a = app.clone();
    let win = app.window();
    dialog.open(win.as_ref(), gio::Cancellable::NONE, move |res| {
        let Some(src) = res.ok().and_then(|f| f.path()) else {
            return;
        };
        let paths = a.paths.clone();
        a.busy(Some("Restoring…"));
        let a2 = a.clone();
        glib::spawn_future_local(async move {
            let r = gio::spawn_blocking(move || backup::read(&paths, &src)).await;
            a2.busy(None);
            match r {
                Ok(Ok(incoming)) => {
                    let n = a2.update(|d| backup::merge(d, incoming));
                    a2.toast(&format!("Restored {n} books"));
                }
                Ok(Err(e)) => a2.toast(&format!("Restore failed: {e}")),
                Err(_) => a2.toast("Restore failed"),
            }
        });
    });
}

fn about(app: &AppRc, content: &gtk::Box) {
    let col = vbox(10);
    col.set_margin_top(12);
    col.append(&label(
        &format!("GBook {} for Linux", env!("CARGO_PKG_VERSION")),
        &["section-title"],
    ));
    col.append(&wrap_label(
        "A lightweight reader for EPUB and PDF with highlights, a feed of ideas from your books and reading stats. Built with GTK 4 and Rust; its library is interchangeable with GBook on Android.",
        &["secondary"],
        5,
    ));
    col.append(&wrap_label(
        "Reading fonts: Literata, Charis SIL, Source Serif 4, Newsreader, Gelasio, Crimson Pro, EB Garamond, Libre Baskerville, Inter and DM Sans, under the SIL Open Font License. Icons: Material Icons (Apache 2.0). PDF rendering: Poppler.",
        &["caption", "text2"],
        6,
    ));
    let gh = gtk::Button::with_label("Source code on GitHub");
    gh.add_css_class("pill");
    gh.set_halign(gtk::Align::Start);
    gh.set_margin_top(8);
    let a = app.clone();
    gh.connect_clicked(move |_| open_uri(&a, "https://github.com/Gsweya/eBook"));
    col.append(&gh);
    content.append(&col);
}
