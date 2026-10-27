//! Reading options, from the reader's top-right button: a live preview, shortcuts to contents, highlights,
//! search and posts, then text size, font, spacing, margins, page theme and page turning.

use std::rc::Rc;

use gbook_core::ReaderSettings;
use gtk::prelude::*;
use gtk::{glib, pango};

use crate::reader::{MAX_FONT, MIN_FONT, Reader, contents_sheet, search_sheet};
use crate::state::AppRc;
use crate::theme::{self, READER_THEMES, READING_FONTS};
use crate::widgets::*;

pub fn show(r: &Rc<Reader>, anchor: &gtk::Button) {
    let content = vbox(14);
    content.set_size_request(400, -1);
    // Room for the scrollbar, so it never covers the right-hand controls.
    content.set_margin_start(4);
    content.set_margin_end(14);
    content.set_margin_bottom(4);
    let scroller = gtk::ScrolledWindow::new();
    scroller.set_hscrollbar_policy(gtk::PolicyType::Never);
    scroller.set_propagate_natural_height(true);
    // Wide enough for the content plus its scrollbar gap, so nothing is clipped.
    scroller.set_min_content_width(418);
    scroller.set_max_content_height(
        (r.app().window().map(|w| w.height()).unwrap_or(800) - 120).max(300),
    );
    scroller.set_child(Some(&content));
    let pop = popover(anchor, &scroller, true);
    pop.set_position(gtk::PositionType::Bottom);
    let render = {
        let (r, content, pop) = (r.clone(), content.clone(), pop.clone());
        move || {
            clear(&content);
            build(&r, &content, &pop);
        }
    };
    render();
    let id = r.app().on_change(render);
    let a = r.app().clone();
    pop.connect_closed(move |_| a.remove_listener(id));
    pop.popup();
}

fn build(r: &Rc<Reader>, content: &gtk::Box, pop: &gtk::Popover) {
    let app = r.app().clone();
    let s = r.settings();
    content.append(&label("Reading options", &["sheet-title"]));
    content.append(&preview(&s, &r.preview_text()));

    let tiles = hbox(8);
    tiles.set_homogeneous(true);
    let items: [(&str, &str, String, Rc<dyn Fn()>); 4] = [
        (
            "format-list-bulleted",
            "Contents",
            format!("{}%", r.progress_percent()),
            {
                let (r, p) = (r.clone(), pop.clone());
                Rc::new(move || {
                    p.popdown();
                    contents_sheet(&r, 0)
                })
            },
        ),
        (
            "format-quote",
            "Highlights",
            r.highlight_count().to_string(),
            {
                let (r, p) = (r.clone(), pop.clone());
                Rc::new(move || {
                    p.popdown();
                    contents_sheet(&r, 1)
                })
            },
        ),
        ("search", "Search", String::new(), {
            let (r, p) = (r.clone(), pop.clone());
            Rc::new(move || {
                p.popdown();
                search_sheet(&r)
            })
        }),
        ("auto-awesome", "Posts", String::new(), {
            let (r, p) = (r.clone(), pop.clone());
            Rc::new(move || {
                p.popdown();
                crate::feed::generate_sheet(r.app(), Some(&r.book_id))
            })
        }),
    ];
    for (ic, name, badge, f) in items {
        let b = gtk::Button::new();
        b.add_css_class("option-tile");
        let v = vbox(4);
        v.append(&icon(ic, 22));
        let l = label(name, &["small", "semibold"]);
        l.set_xalign(0.5);
        v.append(&l);
        let bl = label(&badge, &["small", "text2"]);
        bl.set_xalign(0.5);
        v.append(&bl);
        b.set_child(Some(&v));
        b.connect_clicked(move |_| f());
        tiles.append(&b);
    }
    content.append(&tiles);

    content.append(&label("TEXT & PAGE", &["eyebrow"]));
    // A | size | A stepper
    let seg = hbox(0);
    seg.add_css_class("segment");
    seg.set_homogeneous(false);
    let smaller = gtk::Button::with_label("A");
    smaller.set_hexpand(true);
    smaller.set_sensitive(s.font_size > MIN_FONT);
    let r2 = r.clone();
    smaller.connect_clicked(move |_| r2.change_font(-1.0));
    let bigger = gtk::Button::new();
    let big = gtk::Label::new(Some("A"));
    big.set_attributes(Some(&size_attrs(24)));
    bigger.set_child(Some(&big));
    bigger.set_hexpand(true);
    bigger.set_sensitive(s.font_size < MAX_FONT);
    let r2 = r.clone();
    bigger.connect_clicked(move |_| r2.change_font(1.0));
    let size = label(&format!("{}", s.font_size as i32), &["text2"]);
    size.set_margin_start(14);
    size.set_margin_end(14);
    let div = || {
        let d = vbox(0);
        d.add_css_class("divider");
        d.set_valign(gtk::Align::Center);
        d
    };
    seg.append(&smaller);
    seg.append(&div());
    seg.append(&size);
    seg.append(&div());
    seg.append(&bigger);
    content.append(&seg);

    content.append(&font_picker(&app, &s));
    content.append(&slider_row(
        &app,
        "format-line-spacing",
        "Line spacing",
        &format!("{:.1}×", s.line_height),
        1.1,
        2.2,
        0.05,
        s.line_height as f64,
        |d, v| d.settings.line_height = (v * 20.0).round() as f32 / 20.0,
    ));
    content.append(&slider_row(
        &app,
        "format-indent-increase",
        "Margins",
        &s.margin.to_string(),
        8.0,
        64.0,
        1.0,
        s.margin as f64,
        |d, v| d.settings.margin = v.round() as i32,
    ));
    let presets = hbox(8);
    presets.set_margin_start(34);
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

    let toggles = hbox(8);
    for (name, on, f) in [
        (
            "Bold text",
            s.bold,
            (|d: &mut gbook_core::LibraryData| d.settings.bold = !d.settings.bold)
                as fn(&mut gbook_core::LibraryData),
        ),
        ("Justify", s.justify, |d| {
            d.settings.justify = !d.settings.justify
        }),
    ] {
        let b = gtk::Button::with_label(name);
        b.add_css_class("chip");
        if on {
            b.add_css_class("on");
        }
        let a = app.clone();
        b.connect_clicked(move |_| a.update(f));
        toggles.append(&b);
    }
    content.append(&toggles);

    content.append(&label("THEMES", &["eyebrow"]));
    let grid = gtk::Grid::new();
    grid.set_row_spacing(10);
    grid.set_column_spacing(10);
    grid.set_column_homogeneous(true);
    for (i, t) in READER_THEMES.iter().enumerate() {
        let b = gtk::Button::new();
        b.add_css_class("theme-tile");
        b.add_css_class(&format!("t{i}"));
        if s.theme == i as i32 {
            b.add_css_class("on");
        }
        let v = vbox(0);
        v.set_valign(gtk::Align::Center);
        let aa = gtk::Label::new(Some("Aa"));
        let attrs = size_attrs(22);
        attrs.insert(pango::AttrFontDesc::new(
            &pango::FontDescription::from_string(&format!(
                "{} {}",
                theme::reading_font(&s.font).family,
                if t.bold { "Bold" } else { "" }
            )),
        ));
        aa.set_attributes(Some(&attrs));
        v.append(&aa);
        v.append(&gtk::Label::new(Some(t.name)));
        b.set_child(Some(&v));
        let a = app.clone();
        b.connect_clicked(move |_| a.update(|d| d.settings.theme = i as i32));
        grid.attach(&b, (i % 3) as i32, (i / 3) as i32, 1, 1);
    }
    content.append(&grid);

    content.append(&label("PAGE TURNING", &["eyebrow"]));
    content.append(&page_mode(&app, s.page_mode));
    content.append(&label("FOCUS TRACKER", &["eyebrow"]));
    content.append(&tracker_options(&app, &s));
}

/// The focus tracker: on or off, its step, whether turning it highlights, and where its wheel sits.
pub fn tracker_options(app: &AppRc, s: &ReaderSettings) -> gtk::Widget {
    let col = vbox(12);
    let row = hbox(12);
    let v = vbox(1);
    v.set_hexpand(true);
    v.append(&label("Focus tracker", &[]));
    v.append(&wrap_label(
        "A wheel on the right edge walks a marker through the text: scroll it, drag it, click it, or press . and ,",
        &["caption", "text2"],
        3,
    ));
    row.append(&v);
    let sw = gtk::Switch::new();
    sw.set_active(s.tracker);
    sw.set_valign(gtk::Align::Center);
    let a = app.clone();
    sw.connect_active_notify(move |w| {
        let on = w.is_active();
        a.update(|d| d.settings.tracker = on);
    });
    row.append(&sw);
    col.append(&row);
    if !s.tracker {
        return col.upcast();
    }
    let chips = |title: &str,
                 names: &[&'static str],
                 current: i32,
                 set: fn(&mut gbook_core::LibraryData, i32)| {
        let r = hbox(8);
        let l = label(title, &["secondary"]);
        l.set_hexpand(true);
        r.append(&l);
        for (i, n) in names.iter().enumerate() {
            let b = gtk::Button::with_label(n);
            b.add_css_class("chip");
            if current == i as i32 {
                b.add_css_class("on");
            }
            let a = app.clone();
            b.connect_clicked(move |_| a.update(|d| set(d, i as i32)));
            r.append(&b);
        }
        r
    };
    col.append(&chips(
        "Step by",
        &["Word", "Sentence"],
        s.tracker_unit,
        |d, i| d.settings.tracker_unit = i,
    ));
    let paint = hbox(8);
    let l = label("Highlight as it turns", &["secondary"]);
    l.set_hexpand(true);
    paint.append(&l);
    let off = gtk::Button::new();
    off.add_css_class("hl-swatch");
    off.add_css_class("off");
    off.set_tooltip_text(Some("Off: the wheel only moves the marker"));
    off.set_valign(gtk::Align::Center);
    if s.tracker_color < 0 {
        off.add_css_class("on");
    }
    let a = app.clone();
    off.connect_clicked(move |_| a.update(|d| d.settings.tracker_color = -1));
    paint.append(&off);
    for (i, name) in ["Yellow", "Green", "Blue", "Pink", "Purple"]
        .iter()
        .enumerate()
    {
        let b = gtk::Button::new();
        b.add_css_class("hl-swatch");
        b.add_css_class(&format!("c{i}"));
        b.set_tooltip_text(Some(name));
        b.set_valign(gtk::Align::Center);
        if s.tracker_color == i as i32 {
            b.add_css_class("on");
        }
        let a = app.clone();
        b.connect_clicked(move |_| a.update(|d| d.settings.tracker_color = i as i32));
        paint.append(&b);
    }
    col.append(&paint);
    col.append(&chips(
        "Wheel",
        &["Middle", "Bottom"],
        s.tracker_position,
        |d, i| d.settings.tracker_position = i,
    ));
    col.upcast()
}

fn size_attrs(px: i32) -> pango::AttrList {
    let a = pango::AttrList::new();
    a.insert(pango::AttrSize::new_size_absolute(px * pango::SCALE));
    a
}

/// The page as it will look: theme colours, font, size, spacing, weight and alignment.
fn preview(s: &ReaderSettings, text: &str) -> gtk::Widget {
    let frame = vbox(0);
    frame.add_css_class("theme-tile");
    frame.add_css_class(&format!(
        "t{}",
        s.theme.clamp(0, READER_THEMES.len() as i32 - 1)
    ));
    let l = gtk::Label::new(Some(text));
    l.set_wrap(true);
    l.set_wrap_mode(pango::WrapMode::WordChar);
    l.set_xalign(0.0);
    l.set_lines(5);
    l.set_ellipsize(pango::EllipsizeMode::End);
    l.set_justify(if s.justify {
        gtk::Justification::Fill
    } else {
        gtk::Justification::Left
    });
    let attrs = pango::AttrList::new();
    let mut fd = pango::FontDescription::new();
    fd.set_family(theme::reading_font(&s.font).family);
    fd.set_weight(if s.bold || theme::reader_theme(s.theme).bold {
        pango::Weight::Semibold
    } else {
        pango::Weight::Normal
    });
    fd.set_absolute_size(s.font_size.min(26.0) as f64 * pango::SCALE as f64);
    attrs.insert(pango::AttrFontDesc::new(&fd));
    attrs.insert(pango::AttrFloat::new_line_height(
        s.line_height as f64 * 0.78,
    ));
    l.set_attributes(Some(&attrs));
    l.set_margin_top(16);
    l.set_margin_bottom(16);
    l.set_margin_start(18);
    l.set_margin_end(18);
    frame.append(&l);
    frame.upcast()
}

/// Font row that expands into a list, each name set in its own typeface.
fn font_picker(app: &AppRc, s: &ReaderSettings) -> gtk::Widget {
    let expander = gtk::Expander::new(None);
    let head = hbox(12);
    let aa = gtk::Label::new(Some("Aa"));
    aa.set_attributes(Some(&{
        let a = size_attrs(18);
        a.insert(pango::AttrFontDesc::new(
            &pango::FontDescription::from_string("Literata Bold"),
        ));
        a
    }));
    head.append(&aa);
    let f = label("Font", &[]);
    f.set_hexpand(true);
    head.append(&f);
    head.append(&label(theme::reading_font(&s.font).name, &["text2"]));
    expander.set_label_widget(Some(&head));
    expander.add_css_class("font-picker");
    let list = vbox(0);
    for font in READING_FONTS {
        let b = gtk::Button::new();
        b.add_css_class("menu-row");
        let h = hbox(10);
        let name = gtk::Label::new(Some(font.name));
        name.set_attributes(Some(&{
            let a = size_attrs(17);
            a.insert(pango::AttrFontDesc::new(
                &pango::FontDescription::from_string(font.family),
            ));
            a
        }));
        name.set_xalign(0.0);
        name.set_hexpand(true);
        h.append(&name);
        h.append(&label(
            &format!("like {}", font.like),
            &["caption", "text2"],
        ));
        let check = icon("check", 18);
        check.add_css_class("accent");
        check.set_opacity(if font.id == s.font { 1.0 } else { 0.0 });
        h.append(&check);
        b.set_child(Some(&h));
        let (a, id) = (app.clone(), font.id);
        b.connect_clicked(move |_| a.update(|d| d.settings.font = id.to_string()));
        list.append(&b);
    }
    expander.set_child(Some(&list));
    expander.upcast()
}

#[allow(clippy::too_many_arguments)]
fn slider_row(
    app: &AppRc,
    ic: &str,
    name: &str,
    value: &str,
    min: f64,
    max: f64,
    step: f64,
    current: f64,
    set: fn(&mut gbook_core::LibraryData, f64),
) -> gtk::Widget {
    let col = vbox(2);
    let head = hbox(0);
    head.set_margin_start(34);
    let n = label(name, &["secondary"]);
    n.set_hexpand(true);
    head.append(&n);
    head.append(&label(value, &["secondary"]));
    col.append(&head);
    let row = hbox(12);
    let i = icon(ic, 22);
    i.add_css_class("text2");
    row.append(&i);
    let scale = gtk::Scale::with_range(gtk::Orientation::Horizontal, min, max, step);
    scale.set_value(current);
    scale.set_hexpand(true);
    scale.set_draw_value(false);
    // Apply after the drag pauses, so the book isn't laid out again on every pixel.
    let a = app.clone();
    let pending: Rc<std::cell::RefCell<Option<glib::SourceId>>> = Rc::default();
    scale.connect_value_changed(move |sc| {
        if let Some(t) = pending.borrow_mut().take() {
            t.remove();
        }
        let (a2, v, p2) = (a.clone(), sc.value(), pending.clone());
        let t = glib::timeout_add_local_once(std::time::Duration::from_millis(220), move || {
            p2.borrow_mut().take();
            a2.update(|d| set(d, v));
        });
        *pending.borrow_mut() = Some(t);
    });
    row.append(&scale);
    col.append(&row);
    col.upcast()
}

/// Scroll through the whole chapter, or turn one page at a time.
pub fn page_mode(app: &AppRc, mode: i32) -> gtk::Widget {
    let row = hbox(10);
    row.set_homogeneous(true);
    for (m, ic, name, sub) in [
        (0, "swipe-vertical", "Scroll", "Whole chapter"),
        (1, "auto-stories", "Pages", "Wheel or arrows turn"),
    ] {
        let b = gtk::Button::new();
        b.add_css_class("mode-card");
        if m == mode {
            b.add_css_class("on");
        }
        let h = hbox(10);
        let i = icon(ic, 22);
        i.add_css_class("accent");
        h.append(&i);
        let v = vbox(0);
        v.append(&label(name, &["semibold"]));
        v.append(&label(sub, &["caption", "text2"]));
        h.append(&v);
        b.set_child(Some(&h));
        let a = app.clone();
        b.connect_clicked(move |_| a.update(|d| d.settings.page_mode = m));
        row.append(&b);
    }
    row.upcast()
}
