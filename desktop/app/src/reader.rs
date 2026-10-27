//! The EPUB reader. A chapter at a time goes into a GtkTextView, which brings Pango's shaping and
//! justification, native selection and lazy line layout (long chapters open instantly). Highlights are text
//! tags over the same UTF-16 offsets the Android app stores, so they line up on both.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::rc::{Rc, Weak};
use std::time::{Duration, Instant};

use gbook_core::{
    BookContent, HEADING, Highlight, IMAGE, PARAGRAPH, REACTION_EMOJIS, ReaderSettings,
    ReadingPosition, char_to_utf16, now_ms, utf16_len, utf16_to_char,
};
use gtk::prelude::*;
use gtk::{gdk, glib, pango};

use crate::state::{AppRc, today};
use crate::theme::{self, HIGHLIGHT_COLORS};
use crate::widgets::*;
use crate::{reader_options, sheet};

pub const MIN_FONT: f32 = 12.0;
pub const MAX_FONT: f32 = 40.0;
/// No input for this long means the reader has walked away; that time isn't counted.
const IDLE: Duration = Duration::from_secs(180);
const TOP_PAD: i32 = 64;
const BOTTOM_PAD: i32 = 70;

pub struct Reader {
    app: AppRc,
    pub book_id: String,
    pub content: Rc<BookContent>,
    pub chapter: Cell<usize>,
    root: gtk::Overlay,
    view: crate::pageview::PageView,
    scroller: gtk::ScrolledWindow,
    /// Character offset in the buffer where each block of the current chapter starts.
    starts: RefCell<Vec<i32>>,
    /// Paragraph characters before each chapter, and in total at the end, for progress and page estimates.
    chars_before: Vec<i64>,
    title_text: Marquee,
    page_label: gtk::Label,
    left_label: gtk::Label,
    progress_fill: gtk::Box,
    progress_line: gtk::Overlay,
    mask: gtk::Box,
    fade_top: gtk::Box,
    hud: gtk::Revealer,
    hud_label: gtk::Label,
    hud_gen: Rc<Cell<u32>>,
    layout_key: RefCell<String>,
    highlights_key: RefCell<String>,
    pub search_term: RefCell<Option<String>>,
    pending: Cell<Option<(i32, i32)>>,
    save_timer: RefCell<Option<glib::SourceId>>,
    last_input: Cell<Instant>,
    last_tick: Cell<Instant>,
    unsaved_ms: Cell<i64>,
    page_seen: RefCell<(usize, i32, Instant)>,
    counted: RefCell<HashSet<(usize, i32)>>,
    listener: Cell<usize>,
    timers: RefCell<Vec<glib::SourceId>>,
    col_width: Cell<i32>,
    popover: RefCell<Option<gtk::Popover>>,
    /// Saved highlights in this chapter, drawn with the tracker's marks on top.
    hl_marks: RefCell<Vec<crate::pageview::Mark>>,
    /// Focus tracker: the current (block, unit), each paragraph's units, and where a highlighting turn began.
    track: Cell<Option<(i32, usize)>>,
    units: RefCell<HashMap<i32, Vec<(i32, i32)>>>,
    paint_from: Cell<Option<(i32, usize)>>,
    wheel: RefCell<Option<Rc<crate::tracker::Wheel>>>,
    on_back: Rc<dyn Fn()>,
    closed: Cell<bool>,
}

pub fn open(
    app: &AppRc,

    book: &gbook_core::Book,
    at: Option<(i32, i32)>,
    on_back: Rc<dyn Fn()>,
) -> gtk::Widget {
    let Some(content) = app.content(&book.id).filter(|c| !c.chapters.is_empty()) else {
        let col = vbox(12);
        col.set_valign(gtk::Align::Center);
        col.set_halign(gtk::Align::Center);
        col.append(&label("This book has no readable text", &["section-title"]));
        let b = gtk::Button::with_label("Back to Library");
        b.add_css_class("pill");
        b.connect_clicked(move |_| on_back());
        col.append(&b);
        return col.upcast();
    };
    let mut chars_before = Vec::with_capacity(content.chapters.len() + 1);
    let mut total = 0i64;
    for c in &content.chapters {
        chars_before.push(total);
        total += c
            .blocks
            .iter()
            .filter(|b| b.kind == PARAGRAPH)
            .map(|b| b.text.len() as i64)
            .sum::<i64>()
            .max(1);
    }
    chars_before.push(total);

    let root = gtk::Overlay::new();
    root.add_css_class("reader");
    root.set_focusable(true);
    let view = crate::pageview::PageView::new();
    view.set_editable(false);
    view.set_cursor_visible(false);
    view.set_wrap_mode(gtk::WrapMode::WordChar);
    view.set_top_margin(TOP_PAD + 12);
    view.set_bottom_margin(BOTTOM_PAD + 40);
    view.set_focusable(true);
    let scroller = gtk::ScrolledWindow::new();
    scroller.set_hscrollbar_policy(gtk::PolicyType::Never);
    scroller.set_child(Some(&view));
    root.set_child(Some(&scroller));

    // Watches the page width to keep the text in a readable column.
    let sizer = gtk::DrawingArea::new();
    sizer.set_can_target(false);
    sizer.set_valign(gtk::Align::Start);
    sizer.set_content_height(1);
    root.add_overlay(&sizer);

    let mask = vbox(0);
    mask.add_css_class("page-mask");
    mask.set_valign(gtk::Align::End);
    mask.set_can_target(false);
    mask.set_visible(false);
    root.add_overlay(&mask);

    let fade_top = vbox(0);
    fade_top.add_css_class("page-fade-top");
    fade_top.set_valign(gtk::Align::Start);
    fade_top.set_size_request(-1, TOP_PAD + 6);
    fade_top.set_can_target(false);
    root.add_overlay(&fade_top);
    let fade_bottom = vbox(0);
    fade_bottom.add_css_class("page-fade-bottom");
    fade_bottom.set_valign(gtk::Align::End);
    fade_bottom.set_size_request(-1, BOTTOM_PAD + 10);
    fade_bottom.set_can_target(false);
    root.add_overlay(&fade_bottom);

    // Top bar: back, the chapter (opens the contents), search and reading options.
    let back = circle_button("arrow-back-ios-new", "Back to Library (Esc)");
    let title = gtk::Button::new();
    title.add_css_class("flat");
    title.add_css_class("faint");
    title.add_css_class("reader-chrome");
    // Long chapter names scroll to show themselves in full.
    let title_text = Marquee::new("", &[], Reveal::Auto, 520);
    title.set_child(Some(&title_text.widget));
    title.set_tooltip_text(Some("Contents"));
    let start = hbox(8);
    start.append(&back);
    let end = hbox(10);
    let search_btn = circle_button("search", "Search in book (Ctrl+F)");
    let contents_btn = circle_button("format-list-bulleted", "Contents and highlights");
    let options_btn = circle_button("tune", "Reading options");
    end.append(&contents_btn);
    end.append(&search_btn);
    end.append(&options_btn);
    let top = top_bar(&start, Some(title.upcast_ref()), &end, true, None);
    root.add_overlay(&top);

    // Bottom: page number, pages left in the chapter, and a thin line through the whole book.
    let bottom = vbox(7);
    bottom.set_valign(gtk::Align::End);
    bottom.set_margin_bottom(14);
    bottom.set_margin_start(28);
    bottom.set_margin_end(28);
    bottom.set_can_target(false);
    let row = hbox(0);
    let page_label = label("", &["faint", "caption"]);
    let left_label = label("", &["faint", "caption"]);
    row.append(&page_label);
    row.append(&spacer());
    row.append(&left_label);
    bottom.append(&row);
    let progress_line = gtk::Overlay::new();
    progress_line.add_css_class("progress-line");
    progress_line.set_size_request(-1, 2);
    let progress_fill = hbox(0);
    progress_fill.add_css_class("fill");
    progress_fill.set_halign(gtk::Align::Start);
    progress_line.set_child(Some(&hbox(0)));
    progress_line.add_overlay(&progress_fill);
    bottom.append(&progress_line);
    root.add_overlay(&bottom);

    let hud_label = gtk::Label::new(None);
    hud_label.add_css_class("reader-hud");
    let hud = gtk::Revealer::new();
    hud.set_transition_type(gtk::RevealerTransitionType::Crossfade);
    hud.set_child(Some(&hud_label));
    hud.set_halign(gtk::Align::Center);
    hud.set_valign(gtk::Align::Center);
    hud.set_can_target(false);
    root.add_overlay(&hud);

    let pos = book.position.clone();
    let start_chapter = at
        .map(|(c, _)| c)
        .unwrap_or(pos.chapter)
        .clamp(0, content.chapters.len() as i32 - 1) as usize;
    let r = Rc::new(Reader {
        app: app.clone(),
        book_id: book.id.clone(),
        content,
        chapter: Cell::new(start_chapter),
        root: root.clone(),
        view: view.clone(),
        scroller: scroller.clone(),
        starts: RefCell::new(Vec::new()),
        chars_before,
        title_text,
        page_label,
        left_label,
        progress_fill,
        progress_line,
        mask,
        fade_top: fade_top.clone(),
        hud,
        hud_label,
        hud_gen: Rc::new(Cell::new(0)),
        layout_key: RefCell::new(String::new()),
        highlights_key: RefCell::new(String::new()),
        search_term: RefCell::new(None),
        pending: Cell::new(Some(match at {
            Some((_, b)) => (b, 0),
            None if pos.chapter as usize == start_chapter => (pos.item, pos.offset),
            None => (0, 0),
        })),
        save_timer: RefCell::new(None),
        last_input: Cell::new(Instant::now()),
        last_tick: Cell::new(Instant::now()),
        unsaved_ms: Cell::new(0),
        page_seen: RefCell::new((usize::MAX, -1, Instant::now())),
        counted: RefCell::new(HashSet::new()),
        listener: Cell::new(usize::MAX),
        timers: RefCell::new(Vec::new()),
        col_width: Cell::new(0),
        popover: RefCell::new(None),
        hl_marks: RefCell::new(Vec::new()),
        track: Cell::new(None),
        units: RefCell::new(HashMap::new()),
        paint_from: Cell::new(None),
        wheel: RefCell::new(None),
        on_back,
        closed: Cell::new(false),
    });

    let w = Rc::downgrade(&r);
    back.connect_clicked(move |_| with(&w, |r| r.back()));
    let w = Rc::downgrade(&r);
    title.connect_clicked(move |_| with(&w, |r| contents_sheet(r, 0)));
    let w = Rc::downgrade(&r);
    contents_btn.connect_clicked(move |_| with(&w, |r| contents_sheet(r, 0)));
    let w = Rc::downgrade(&r);
    search_btn.connect_clicked(move |_| with(&w, search_sheet));
    let w = Rc::downgrade(&r);
    options_btn.connect_clicked(move |b| with(&w, |r| reader_options::show(r, b)));

    let w = Rc::downgrade(&r);
    sizer.connect_resize(move |_, width, _| with(&w, |r| r.fit_column(width)));
    let w = Rc::downgrade(&r);
    scroller
        .vadjustment()
        .connect_value_changed(move |_| with(&w, |r| r.on_scroll()));
    let w = Rc::downgrade(&r);
    scroller
        .vadjustment()
        .connect_changed(move |_| with(&w, |r| r.update_status()));

    let w1 = Rc::downgrade(&r);
    let w2 = Rc::downgrade(&r);
    let w3 = Rc::downgrade(&r);
    let wheel = Rc::new(crate::tracker::Wheel::new(
        app,
        crate::tracker::WheelInput {
            step: Box::new(move |fwd| w1.upgrade().is_some_and(|r| r.step_track(fwd))),
            press: Box::new(move || with(&w2, |r| r.start_paint())),
            release: Box::new(move || with(&w3, |r| r.finish_paint())),
        },
    ));
    root.add_overlay(&wheel.widget);
    *r.wheel.borrow_mut() = Some(wheel);
    r.install_input(&root);
    r.render();
    CURRENT.with(|c| *c.borrow_mut() = Rc::downgrade(&r));

    let w = Rc::downgrade(&r);
    r.listener
        .set(app.on_change(move || with(&w, |r| r.on_data_changed())));
    let w = Rc::downgrade(&r);
    let tick = glib::timeout_add_local(Duration::from_secs(5), move || match w.upgrade() {
        Some(r) => {
            r.tick();
            glib::ControlFlow::Continue
        }
        None => glib::ControlFlow::Break,
    });
    r.timers.borrow_mut().push(tick);
    // The reader lives as long as its widget: this handler holds the only strong reference.
    let keep = RefCell::new(Some(r.clone()));
    root.connect_unrealize(move |_| {
        if let Some(r) = keep.borrow_mut().take() {
            r.close();
        }
    });
    root.upcast()
}

thread_local! {
    /// The open reader, for the dev tools.
    pub static CURRENT: RefCell<Weak<Reader>> = const { RefCell::new(Weak::new()) };
}

fn with(w: &Weak<Reader>, f: impl FnOnce(&Rc<Reader>)) {
    if let Some(r) = w.upgrade() {
        f(&r);
    }
}

impl Reader {
    pub fn app(&self) -> &AppRc {
        &self.app
    }

    pub fn settings(&self) -> ReaderSettings {
        self.app.data().settings.clone()
    }

    fn back(&self) {
        (self.on_back)();
    }

    /// Stops timers and records the reading time still pending; runs once as the reader goes away.
    fn close(&self) {
        if self.closed.replace(true) {
            return;
        }
        self.accrue();
        self.flush_time();
        self.save_position();
        for t in self.timers.borrow_mut().drain(..) {
            t.remove();
        }
        if let Some(t) = self.save_timer.borrow_mut().take() {
            t.remove();
        }
        self.app.remove_listener(self.listener.get());
        if let Some(p) = self.popover.borrow_mut().take() {
            p.popdown();
        }
    }

    fn chapter(&self) -> &gbook_core::Chapter {
        &self.content.chapters[self.chapter.get()]
    }

    /// Fingerprint of everything that changes the text layout.
    fn layout_fingerprint(s: &ReaderSettings, dark: bool) -> String {
        format!(
            "{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{dark}",
            s.font_size,
            s.line_height,
            s.font,
            s.bold,
            s.justify,
            s.theme,
            s.margin,
            s.page_mode,
            s.tracker,
            s.tracker_unit,
            s.tracker_position
        )
    }

    fn highlights_fingerprint(&self) -> String {
        let d = self.app.data();
        let ch = self.chapter.get() as i32;
        let mut k = String::new();
        for h in d
            .highlights
            .iter()
            .filter(|h| h.book_id == self.book_id && h.chapter == ch)
        {
            k += &format!(
                "{}:{}:{}:{}:{}:{}:{};",
                h.id,
                h.block,
                h.start,
                h.last_block(),
                h.end,
                h.color,
                h.style
            );
        }
        k + &format!("{:?}", self.search_term.borrow())
    }

    fn on_data_changed(self: &Rc<Self>) {
        if self.app.data().book(&self.book_id).is_none() {
            return;
        }
        let s = self.settings();
        if Self::layout_fingerprint(&s, self.app.dark()) != *self.layout_key.borrow() {
            // Keep the reader's place across the re-layout.
            let (b, off) = self.top_block();
            self.pending.set(Some((b, off)));
            self.render();
        } else if self.highlights_fingerprint() != *self.highlights_key.borrow() {
            self.apply_highlights();
        }
    }

    /// Lays out the current chapter.
    fn render(self: &Rc<Self>) {
        let s = self.settings();
        self.units.borrow_mut().clear();
        self.track.set(None);
        self.paint_from.set(None);
        if let Some(w) = self.wheel.borrow().as_ref() {
            w.widget.set_visible(s.tracker);
            if s.tracker_position == 1 {
                w.widget.set_valign(gtk::Align::End);
                w.widget.set_margin_bottom(BOTTOM_PAD + 24);
            } else {
                w.widget.set_valign(gtk::Align::Center);
                w.widget.set_margin_bottom(0);
            }
        }
        let dark = self.app.dark();
        *self.layout_key.borrow_mut() = Self::layout_fingerprint(&s, dark);
        for i in 0..theme::READER_THEMES.len() {
            self.root.remove_css_class(&format!("t{i}"));
        }
        self.root.add_css_class(&format!(
            "t{}",
            s.theme.clamp(0, theme::READER_THEMES.len() as i32 - 1)
        ));
        let rt = theme::reader_theme(s.theme);
        let (_, ink) = theme::page_colors(&s, dark);
        let paged = s.page_mode == 1;
        self.scroller.set_vscrollbar_policy(if paged {
            gtk::PolicyType::External
        } else {
            gtk::PolicyType::Automatic
        });
        self.mask.set_visible(paged);
        // One page at a time: nothing shows through above the page either.
        if paged {
            self.fade_top.add_css_class("solid");
            self.fade_top.set_size_request(-1, TOP_PAD - 2);
        } else {
            self.fade_top.remove_css_class("solid");
            self.fade_top.set_size_request(-1, TOP_PAD + 6);
        }
        self.fit_column(self.root.width());

        let buffer = gtk::TextBuffer::new(None);
        let family = theme::reading_font(&s.font).family;
        let px = s.font_size.clamp(MIN_FONT, MAX_FONT);
        let weight = if s.bold || rt.bold {
            pango::Weight::Semibold
        } else {
            pango::Weight::Normal
        };
        let fd = |scale: f32, w: pango::Weight| {
            let mut f = pango::FontDescription::new();
            f.set_family(family);
            f.set_weight(w);
            f.set_absolute_size((px * scale) as f64 * pango::SCALE as f64);
            f
        };
        // Pango's line height is a factor of the font's own line height; the setting is a factor of its size.
        let ctx = self.view.pango_context();
        let line_factor = |f: &pango::FontDescription, size: f32, lh: f32| {
            let m = ctx.metrics(Some(f), None);
            let natural = (m.ascent() + m.descent()) as f32 / pango::SCALE as f32;
            if natural > 0.0 {
                (size * lh / natural).max(0.8)
            } else {
                1.0
            }
        };
        let body_fd = fd(1.0, weight);
        let p = gtk::TextTag::new(Some("p"));
        p.set_font_desc(Some(&body_fd));
        p.set_indent((px * 1.4) as i32);
        p.set_pixels_below_lines((px * 0.45) as i32);
        p.set_line_height(line_factor(&body_fd, px, s.line_height));
        p.set_foreground_rgba(Some(&ink.rgba(1.0)));
        p.set_justification(if s.justify {
            gtk::Justification::Fill
        } else {
            gtk::Justification::Left
        });
        buffer.tag_table().add(&p);
        for (name, scale, lh) in [("h-big", 1.5, 1.9 / 1.5), ("h-small", 1.2, 1.55 / 1.2)] {
            let f = fd(scale, pango::Weight::Bold);
            let t = gtk::TextTag::new(Some(name));
            t.set_font_desc(Some(&f));
            t.set_justification(gtk::Justification::Center);
            t.set_pixels_above_lines(28);
            t.set_pixels_below_lines(16);
            t.set_line_height(line_factor(&f, px * scale, lh));
            t.set_foreground_rgba(Some(&ink.rgba(1.0)));
            buffer.tag_table().add(&t);
        }
        let img = gtk::TextTag::new(Some("img"));
        img.set_justification(gtk::Justification::Center);
        img.set_pixels_above_lines(12);
        img.set_pixels_below_lines(12);
        buffer.tag_table().add(&img);
        let search = gtk::TextTag::new(Some("search"));
        search.set_background_rgba(Some(&theme::SELECTION.rgba(0.55)));
        buffer.tag_table().add(&search);

        let ch = self.chapter();
        let mut starts = Vec::with_capacity(ch.blocks.len());
        let max_img = self.col_width.get().clamp(400, 1000);
        for (i, b) in ch.blocks.iter().enumerate() {
            let mut end = buffer.end_iter();
            starts.push(end.offset());
            match b.kind {
                HEADING => {
                    let tag = if b.level <= 2 { "h-big" } else { "h-small" };
                    buffer.insert_with_tags_by_name(&mut end, &b.text, &[tag]);
                }
                IMAGE => {
                    let path = self
                        .app
                        .paths
                        .images_dir(&self.book_id)
                        .join(b.src.as_deref().unwrap_or(""));
                    let scale = self.app.ui_scale();
                    if let Some(dec) = crate::media::decode_scaled(&path, max_img * scale) {
                        let tex = dec.texture();
                        // Shown at its decoded size divided by the screen scale, and no taller than 520px.
                        let (w, h) = (
                            dec.width as f32 / scale as f32,
                            dec.height as f32 / scale as f32,
                        );
                        let k = (520.0 / h).min(1.0);
                        let paintable = ScaledPaintable::new(&tex, (w * k) as i32, (h * k) as i32);
                        let start_off = end.offset();
                        buffer.insert_paintable(&mut end, &paintable);
                        let s_it = buffer.iter_at_offset(start_off);
                        buffer.apply_tag_by_name("img", &s_it, &end);
                    }
                }
                _ => {
                    buffer.insert_with_tags_by_name(&mut end, &b.text, &["p"]);
                }
            }
            if i + 1 < ch.blocks.len() {
                let mut end = buffer.end_iter();
                buffer.insert(&mut end, "\n");
            }
        }
        *self.starts.borrow_mut() = starts;
        // End of chapter: on to the next one.
        let mut end = buffer.end_iter();
        buffer.insert(&mut end, "\n");
        let anchor_at = buffer.end_iter().offset();
        let anchor = buffer.create_child_anchor(&mut buffer.end_iter());
        buffer.apply_tag_by_name("img", &buffer.iter_at_offset(anchor_at), &buffer.end_iter());
        self.view.set_buffer(Some(&buffer));
        self.view.add_child_at_anchor(&self.chapter_end(), &anchor);
        self.title_text.set_text(&ch.title);
        self.apply_highlights();

        let (b, off) = self.pending.take().unwrap_or((0, 0));
        self.scroll_to_block(b, off);
        self.update_status();
    }

    fn chapter_end(self: &Rc<Self>) -> gtk::Widget {
        let col = vbox(10);
        col.set_margin_top(40);
        col.set_margin_bottom(20);
        let next = self.content.chapters.get(self.chapter.get() + 1);
        if let Some(n) = next {
            let b = gtk::Button::new();
            b.add_css_class("pill");
            b.add_css_class("chapter-next");
            let h = hbox(8);
            let l = label(&format!("Next: {}", n.title), &[]);
            l.set_ellipsize(pango::EllipsizeMode::End);
            l.set_max_width_chars(40);
            h.append(&l);
            h.append(&icon("chevron-right", 20));
            b.set_child(Some(&h));
            let w = Rc::downgrade(self);
            b.connect_clicked(move |_| with(&w, |r| r.go_to(r.chapter.get() + 1, 0)));
            col.append(&b);
        } else {
            let l = label("The end", &["faint", "serif-title"]);
            l.set_xalign(0.5);
            col.append(&l);
        }
        let posts = gtk::Button::with_label("Create posts from this book");
        posts.add_css_class("flat");
        posts.add_css_class("faint");
        let (a, id) = (self.app.clone(), self.book_id.clone());
        posts.connect_clicked(move |_| crate::feed::generate_sheet(&a, Some(&id)));
        col.append(&posts);
        col.set_halign(gtk::Align::Center);
        col.upcast()
    }

    /// Centres the text in a column whose width follows the margin setting.
    fn fit_column(&self, width: i32) {
        if width <= 0 {
            return;
        }
        let m = self.app.data().settings.margin;
        let max = (900.0 - m as f32 * 5.6).clamp(420.0, 900.0) as i32;
        let side = ((width - max) / 2).max(28.max(m));
        // With the tracker on, the text keeps clear of its wheel.
        let right = if self.app.data().settings.tracker {
            side.max(84)
        } else {
            side
        };
        if self.view.left_margin() != side || self.view.right_margin() != right {
            self.view.set_left_margin(side);
            self.view.set_right_margin(right);
        }
        self.col_width.set(width - side - right);
    }

    fn buffer(&self) -> gtk::TextBuffer {
        self.view.buffer()
    }

    /// Buffer iterator for UTF-16 offset `off` in block `b`.
    fn iter_at(&self, b: i32, off: i32) -> gtk::TextIter {
        let starts = self.starts.borrow();
        let buf = self.buffer();
        let Some(&start) = starts.get(b.max(0) as usize) else {
            return buf.end_iter();
        };
        let text = &self.chapter().blocks[b as usize].text;
        buf.iter_at_offset(start + utf16_to_char(text, off))
    }

    /// Block index and UTF-16 offset for a buffer character offset.
    fn block_pos(&self, offset: i32) -> (i32, i32) {
        let starts = self.starts.borrow();
        let b = match starts.binary_search(&offset) {
            Ok(i) => i,
            Err(i) => i.saturating_sub(1),
        };
        let text = self
            .chapter()
            .blocks
            .get(b)
            .map(|x| x.text.as_str())
            .unwrap_or("");
        let within =
            (offset - starts.get(b).copied().unwrap_or(0)).clamp(0, text.chars().count() as i32);
        (b as i32, char_to_utf16(text, within))
    }

    fn apply_highlights(&self) {
        *self.highlights_key.borrow_mut() = self.highlights_fingerprint();
        let buf = self.buffer();
        let (s, e) = buf.bounds();
        buf.remove_tag_by_name("search", &s, &e);
        let d = self.app.data();
        let ch = self.chapter.get() as i32;
        let mut marks = Vec::new();
        for h in d
            .highlights
            .iter()
            .filter(|h| h.book_id == self.book_id && h.chapter == ch)
        {
            let blocks = &self.chapter().blocks;
            let last = h.last_block().min(blocks.len() as i32 - 1);
            let len_end = blocks
                .get(last as usize)
                .map(|b| utf16_len(&b.text))
                .unwrap_or(0);
            let (_, end) = h.range_in(last, len_end);
            let color =
                HIGHLIGHT_COLORS[h.color.rem_euclid(HIGHLIGHT_COLORS.len() as i32) as usize];
            marks.push(crate::pageview::Mark {
                start: self.iter_at(h.block, h.start.max(0)).offset(),
                end: self.iter_at(last, end).offset(),
                fill: (h.style != 1).then(|| color.rgba(0.38)),
                underline: (h.style == 1).then(|| color.rgba(1.0)),
                thin: false,
                reaction: h.reaction.clone(),
            });
        }
        *self.hl_marks.borrow_mut() = marks;
        self.refresh_marks();
        drop(d);
        if let Some(term) = self.search_term.borrow().as_ref().filter(|t| !t.is_empty()) {
            let mut it = buf.start_iter();
            while let Some((a, b)) = it.forward_search(
                term,
                gtk::TextSearchFlags::CASE_INSENSITIVE | gtk::TextSearchFlags::TEXT_ONLY,
                None,
            ) {
                buf.apply_tag_by_name("search", &a, &b);
                it = b;
            }
        }
    }

    /// Scrolls so block `b` is at the top of the page, `offset` pixels in.
    fn scroll_to_block(self: &Rc<Self>, b: i32, offset: i32) {
        let n = self.starts.borrow().len() as i32;
        if b <= 0 && offset <= 0 {
            self.scroller.vadjustment().set_value(0.0);
            return;
        }
        let buf = self.buffer();
        let it = if b >= n {
            buf.end_iter()
        } else {
            self.iter_at(b, 0)
        };
        let mark = buf.create_mark(None, &it, true);
        // Lines below are laid out lazily; scrolling to a mark waits for that layout.
        self.view.scroll_to_mark(&mark, 0.0, true, 0.0, 0.0);
        let w = Rc::downgrade(self);
        glib::timeout_add_local_once(Duration::from_millis(60), move || {
            with(&w, |r| {
                let rect = r.view.iter_location(&r.buffer().iter_at_mark(&mark));
                let adj = r.scroller.vadjustment();
                let delta = r.to_widget_y(rect.y()) - (TOP_PAD + 12) as f64 + offset as f64;
                adj.set_value((adj.value() + delta).max(0.0));
                r.buffer().delete_mark(&mark);
                r.update_status();
            })
        });
    }

    /// The block at the top of the page and how many pixels of it are scrolled past.
    fn top_block(&self) -> (i32, i32) {
        let y = self.to_buffer_y((TOP_PAD + 12) as f64);
        let (it, _) = self.view.line_at_y(y);
        let (b, _) = self.block_pos(it.offset());
        let top = self.view.iter_location(&self.iter_at(b, 0)).y();
        (b, (y - top).max(0))
    }

    /// Buffer y for a y in the text view's own coordinates, and back.
    fn to_buffer_y(&self, wy: f64) -> i32 {
        self.view
            .window_to_buffer_coords(gtk::TextWindowType::Widget, 0, wy.round() as i32)
            .1
    }

    fn to_widget_y(&self, by: i32) -> f64 {
        self.view
            .buffer_to_window_coords(gtk::TextWindowType::Widget, 0, by)
            .1 as f64
    }

    /// The wrapped (display) line at buffer y: its first character and its top and bottom in buffer coordinates.
    /// GtkTextView's own "line" calls work on whole paragraphs, which is too coarse for pages.
    fn display_line(&self, by: i32) -> (gtk::TextIter, i32, i32) {
        let x = self.view.left_margin() + 2;
        let mut it = self
            .view
            .iter_at_location(x, by)
            .unwrap_or_else(|| self.view.line_at_y(by).0);
        self.view.backward_display_line_start(&mut it);
        let r = self.view.iter_location(&it);
        (it, r.y(), r.y() + r.height())
    }

    /// Top of the display line after the one starting at `it`, in buffer coordinates.
    fn next_display_line_top(&self, it: &gtk::TextIter) -> Option<i32> {
        let mut n = *it;
        if !self.view.forward_display_line(&mut n) || n.offset() <= it.offset() {
            return None;
        }
        Some(self.view.iter_location(&n).y())
    }

    pub fn go_to(self: &Rc<Self>, chapter: usize, block: i32) {
        let chapter = chapter.min(self.content.chapters.len() - 1);
        if chapter == self.chapter.get() {
            self.scroll_to_block(block, 0);
            return;
        }
        self.save_position();
        self.chapter.set(chapter);
        self.pending.set(Some((block, 0)));
        self.render();
        self.save_position();
    }

    /// Height of the text area between the top and bottom chrome.
    fn page_height(&self) -> f64 {
        (self.scroller.height() - TOP_PAD - BOTTOM_PAD).max(100) as f64
    }

    /// Turns a page: the next page starts with the line cut off at the bottom of this one, and no page ever
    /// starts with half a line.
    pub fn turn(self: &Rc<Self>, forward: bool) {
        let adj = self.scroller.vadjustment();
        let max = (adj.upper() - adj.page_size()).max(0.0);
        let value = adj.value();
        let ph = self.page_height();
        let target = if forward {
            if value >= max - 1.0 {
                if self.chapter.get() + 1 < self.content.chapters.len() {
                    self.go_to(self.chapter.get() + 1, 0);
                }
                return;
            }
            let bottom = self.to_buffer_y(TOP_PAD as f64 + ph);
            let (it, top, bot) = self.display_line(bottom);
            let next = if bot <= bottom {
                self.next_display_line_top(&it).unwrap_or(bot)
            } else {
                top
            };
            let delta = self.to_widget_y(next) - TOP_PAD as f64;
            value + if delta < 8.0 { ph } else { delta }
        } else {
            if value <= 0.5 {
                if self.chapter.get() > 0 {
                    // The previous chapter opens on its last page.
                    self.go_to(self.chapter.get() - 1, i32::MAX);
                }
                return;
            }
            let want = self.to_buffer_y(TOP_PAD as f64) - ph as i32;
            let (it, top, _) = self.display_line(want);
            // A line cut by the top edge would be shown twice; start at the next one instead.
            let first = if top < want - 1 {
                self.next_display_line_top(&it).unwrap_or(top)
            } else {
                top
            };
            let t = value + self.to_widget_y(first) - TOP_PAD as f64;
            if t <= ph * 0.25 {
                0.0
            } else {
                t.min(value - 1.0)
            }
        };
        animate_to(&self.scroller, target.clamp(0.0, max));
        self.touch();
    }

    fn on_scroll(self: &Rc<Self>) {
        self.touch();
        self.update_status();
        if let Some(t) = self.save_timer.borrow_mut().take() {
            t.remove();
        }
        let w = Rc::downgrade(self);
        let t = glib::timeout_add_local_once(Duration::from_millis(400), move || {
            with(&w, |r| {
                r.save_timer.borrow_mut().take();
                r.save_position();
            })
        });
        *self.save_timer.borrow_mut() = Some(t);
    }

    /// (page in chapter, pages in chapter, page in book, pages in book). The chapter's pages come from its laid-out
    /// height; the book's are estimated from how much text fits on a page at the current size and column width.
    fn pages(&self) -> (i32, i32, i32, i32) {
        let adj = self.scroller.vadjustment();
        let ph = self.page_height();
        let text_h = (adj.upper() - (TOP_PAD + 12) as f64 - (BOTTOM_PAD + 40) as f64).max(1.0);
        let in_ch = ((text_h / ph).ceil() as i32).max(1);
        let max = (adj.upper() - adj.page_size()).max(0.0);
        let cur = if max <= 0.0 || adj.value() >= max - 1.0 {
            in_ch - 1
        } else {
            ((adj.value() / ph).floor() as i32).min(in_ch - 1)
        };
        let s = self.app.data().settings.clone();
        let px = s.font_size.clamp(MIN_FONT, MAX_FONT) as f64;
        // Average glyph advance is about half the font size in these serif faces.
        let per_line = (self.col_width.get().max(200) as f64 / (px * 0.5)).max(10.0);
        let lines = (ph / (px * s.line_height as f64) - 1.0).max(4.0);
        let per_page = per_line * lines * 0.92;
        let c = self.chapter.get();
        let total = self.chars_before[self.chars_before.len() - 1] as f64;
        let before = (self.chars_before[c] as f64 / per_page).round() as i32;
        let book_pages = ((total / per_page).round() as i32).max(before + in_ch);
        (cur, in_ch, (before + cur + 1).min(book_pages), book_pages)
    }

    fn progress(&self) -> f32 {
        let adj = self.scroller.vadjustment();
        let max = (adj.upper() - adj.page_size()).max(0.0);
        let frac = if max <= 0.0 {
            1.0
        } else {
            (adj.value() / max).clamp(0.0, 1.0)
        };
        let c = self.chapter.get();
        let total = self.chars_before[self.chars_before.len() - 1] as f64;
        let here = self.chars_before[c] as f64
            + (self.chars_before[c + 1] - self.chars_before[c]) as f64 * frac;
        (here / total.max(1.0)) as f32
    }

    fn update_status(&self) {
        let (cur, in_ch, page, total) = self.pages();
        self.page_label.set_text(&format!("Page {page} of {total}"));
        let left = in_ch - 1 - cur;
        self.left_label.set_text(&match left {
            0 => "Last page in chapter".to_string(),
            1 => "1 page left in chapter".to_string(),
            n => format!("{n} pages left in chapter"),
        });
        let w = self.progress_line.width();
        self.progress_fill
            .set_size_request(((w as f32) * page as f32 / total.max(1) as f32) as i32, 2);
        if self.app.data().settings.page_mode == 1 {
            self.place_mask();
        }
        let mut seen = self.page_seen.borrow_mut();
        if seen.0 != self.chapter.get() || seen.1 != cur {
            *seen = (self.chapter.get(), cur, Instant::now());
        }
    }

    /// In page mode, hides the line cut off at the bottom of the page: it opens the next page instead.
    fn place_mask(&self) {
        let h = self.scroller.height() as f64;
        if h <= (TOP_PAD + BOTTOM_PAD) as f64 {
            return;
        }
        let bottom_w = h - BOTTOM_PAD as f64;
        let bottom = self.to_buffer_y(bottom_w);
        let (it, top, bot) = self.display_line(bottom);
        let cut = if bot <= bottom {
            self.next_display_line_top(&it).unwrap_or(bot)
        } else {
            top
        };
        let cut_w = self.to_widget_y(cut).clamp(TOP_PAD as f64, h);
        self.mask.set_size_request(-1, (h - cut_w).max(0.0) as i32);
    }

    fn save_position(&self) {
        if self.starts.borrow().is_empty() {
            return;
        }
        let (item, offset) = self.top_block();
        let progress = self.progress();
        let id = self.book_id.clone();
        let ch = self.chapter.get() as i32;
        self.app.update_quiet(|d| {
            let mut pos = d.book(&id).map(|b| b.position.clone()).unwrap_or_default();
            pos = ReadingPosition {
                chapter: ch,
                item,
                offset,
                ..pos
            };
            d.save_position(&id, pos, progress);
        });
    }

    fn touch(&self) {
        self.last_input.set(Instant::now());
    }

    /// Adds the time since the last tick if the reader is active: window focused, input within the last few minutes.
    fn accrue(&self) {
        let now = Instant::now();
        let since = now.duration_since(self.last_tick.replace(now));
        let active = self.app.window().is_some_and(|w| w.is_active())
            && now.duration_since(self.last_input.get()) < IDLE;
        if active && self.root.is_mapped() {
            self.unsaved_ms
                .set(self.unsaved_ms.get() + since.as_millis() as i64);
        }
    }

    fn flush_time(&self) {
        let ms = self.unsaved_ms.replace(0);
        if ms <= 0 {
            return;
        }
        let (_, day) = today();
        let id = self.book_id.clone();
        let reached = self.app.update_quiet(|d| d.add_reading_time(&id, ms, &day));
        if let Some(min) = reached {
            self.app.toast(&format!(
                "🎉 Daily goal reached! {min} min today — keep going, the extra time counts too."
            ));
        }
    }

    fn tick(&self) {
        self.accrue();
        if self.unsaved_ms.get() >= 30_000 {
            self.flush_time();
        }
        // A page counts as read once it has stayed on screen for a few seconds.
        let (ch, page, since) = *self.page_seen.borrow();
        if ch != usize::MAX
            && since.elapsed() >= Duration::from_secs(4)
            && self.counted.borrow_mut().insert((ch, page))
        {
            let (_, day) = today();
            let id = self.book_id.clone();
            self.app.update_quiet(|d| d.add_pages_read(&id, 1, &day));
        }
    }

    pub fn show_hud(&self, text: &str) {
        self.hud_label.set_text(text);
        self.hud.set_reveal_child(true);
        // Only the latest call hides it, so holding a key keeps it up.
        let generation = self.hud_gen.get() + 1;
        self.hud_gen.set(generation);
        let (h, g) = (self.hud.clone(), self.hud_gen.clone());
        glib::timeout_add_local_once(Duration::from_millis(900), move || {
            if g.get() == generation {
                h.set_reveal_child(false);
            }
        });
    }

    pub fn change_font(&self, delta: f32) {
        let n = (self.settings().font_size + delta).clamp(MIN_FONT, MAX_FONT);
        self.app.update(|d| d.settings.font_size = n);
        self.show_hud(&format!("{} pt", n as i32));
    }

    fn install_input(self: &Rc<Self>, root: &gtk::Overlay) {
        let keys = gtk::EventControllerKey::new();
        keys.set_propagation_phase(gtk::PropagationPhase::Capture);
        let w = Rc::downgrade(self);
        keys.connect_key_pressed(move |_, key, _, mods| {
            let Some(r) = w.upgrade() else {
                return glib::Propagation::Proceed;
            };
            r.touch();
            let ctrl = mods.contains(gdk::ModifierType::CONTROL_MASK);
            let shift = mods.contains(gdk::ModifierType::SHIFT_MASK);
            let paged = r.app.data().settings.page_mode == 1;
            let adj = r.scroller.vadjustment();
            match key {
                gdk::Key::Escape => r.back(),
                gdk::Key::Right | gdk::Key::Page_Down => r.turn(true),
                gdk::Key::Left | gdk::Key::Page_Up => r.turn(false),
                gdk::Key::space => r.turn(!shift),
                gdk::Key::Down | gdk::Key::j if !ctrl => {
                    if paged {
                        r.turn(true)
                    } else {
                        adj.set_value(adj.value() + 60.0)
                    }
                }
                gdk::Key::Up | gdk::Key::k if !ctrl => {
                    if paged {
                        r.turn(false)
                    } else {
                        adj.set_value(adj.value() - 60.0)
                    }
                }
                gdk::Key::Home => adj.set_value(0.0),
                gdk::Key::End => adj.set_value(adj.upper()),
                gdk::Key::bracketright => r.go_to(r.chapter.get() + 1, 0),
                gdk::Key::bracketleft => r.go_to(r.chapter.get().saturating_sub(1), 0),
                gdk::Key::equal | gdk::Key::plus | gdk::Key::KP_Add if ctrl => r.change_font(1.0),
                gdk::Key::minus | gdk::Key::KP_Subtract if ctrl => r.change_font(-1.0),
                gdk::Key::_0 if ctrl => {
                    r.app.update(|d| d.settings.font_size = 19.0);
                    r.show_hud("19 pt");
                }
                gdk::Key::f if ctrl => search_sheet(&r),
                gdk::Key::t if ctrl => contents_sheet(&r, 0),
                gdk::Key::period | gdk::Key::comma if r.app.data().settings.tracker => {
                    let fwd = key == gdk::Key::period;
                    if r.step_track(fwd)
                        && let Some(w) = r.wheel.borrow().as_ref()
                    {
                        w.nudge(fwd);
                    }
                }
                gdk::Key::F11 => {
                    if let Some(win) = r.app.window() {
                        if win.is_fullscreen() {
                            win.unfullscreen()
                        } else {
                            win.fullscreen()
                        }
                    }
                }
                _ => return glib::Propagation::Proceed,
            }
            glib::Propagation::Stop
        });
        root.add_controller(keys);

        // In page mode the wheel turns pages instead of scrolling.
        let wheel = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::VERTICAL);
        wheel.set_propagation_phase(gtk::PropagationPhase::Capture);
        let acc = Rc::new(Cell::new(0.0f64));
        let w = Rc::downgrade(self);
        wheel.connect_scroll(move |c, _, dy| {
            let Some(r) = w.upgrade() else {
                return glib::Propagation::Proceed;
            };
            r.touch();
            if c.current_event_state()
                .contains(gdk::ModifierType::CONTROL_MASK)
            {
                r.change_font(if dy < 0.0 { 1.0 } else { -1.0 });
                return glib::Propagation::Stop;
            }
            if r.app.data().settings.page_mode != 1 {
                return glib::Propagation::Proceed;
            }
            let smooth = c.unit() == gdk::ScrollUnit::Surface;
            acc.set(acc.get() + dy);
            let threshold = if smooth { 60.0 } else { 1.0 };
            if acc.get().abs() >= threshold {
                r.turn(acc.get() > 0.0);
                acc.set(0.0);
            }
            glib::Propagation::Stop
        });
        self.scroller.add_controller(wheel);

        // Selection and clicks on highlights: act once the button is released and the text view has updated.
        let legacy = gtk::EventControllerLegacy::new();
        let w = Rc::downgrade(self);
        let pressed_at = Rc::new(Cell::new(Instant::now()));
        legacy.connect_event(move |_, ev| {
            let Some(r) = w.upgrade() else {
                return glib::Propagation::Proceed;
            };
            match ev.event_type() {
                gdk::EventType::ButtonPress => {
                    r.touch();
                    pressed_at.set(Instant::now());
                }
                gdk::EventType::ButtonRelease => {
                    let btn = ev
                        .downcast_ref::<gdk::ButtonEvent>()
                        .map(|b| b.button())
                        .unwrap_or(1);
                    if btn == 1 {
                        let w2 = Rc::downgrade(&r);
                        glib::idle_add_local_once(move || with(&w2, |r| r.after_click()));
                    }
                }
                _ => {}
            }
            glib::Propagation::Proceed
        });
        self.view.add_controller(legacy);
        let motion = gtk::EventControllerMotion::new();
        let w = Rc::downgrade(self);
        motion.connect_motion(move |_, _, _| with(&w, |r| r.touch()));
        root.add_controller(motion);
    }

    /// Selects UTF-16 range `start..end` of block `b` and acts as if the mouse had just made that selection.
    pub fn dev_select(self: &Rc<Self>, b: i32, start: i32, end: i32) {
        let (s, e) = (self.iter_at(b, start), self.iter_at(b, end));
        self.buffer().select_range(&s, &e);
        self.after_click();
    }

    pub fn dev_click(self: &Rc<Self>, b: i32, off: i32) {
        let it = self.iter_at(b, off);
        self.buffer().place_cursor(&it);
        self.after_click();
    }

    fn after_click(self: &Rc<Self>) {
        let buf = self.buffer();
        if let Some((a, b)) = buf.selection_bounds()
            && a.offset() != b.offset()
        {
            let (sb, so) = self.block_pos(a.offset());
            let (eb, eo) = self.block_pos(b.offset());
            if sb == eb && so == eo {
                return;
            }
            self.selection_popover(
                Selection {
                    start: (sb, so),
                    end: (eb, eo),
                },
                None,
            );
            return;
        }
        let it = buf.iter_at_mark(&buf.get_insert());
        let (b, off) = self.block_pos(it.offset());
        let ch = self.chapter.get() as i32;
        let hit = {
            let d = self.app.data();
            let blocks = &self.chapter().blocks;
            d.highlights
                .iter()
                .find(|h| {
                    h.book_id == self.book_id && h.chapter == ch && h.covers(b) && {
                        let len = blocks
                            .get(b as usize)
                            .map(|x| utf16_len(&x.text))
                            .unwrap_or(0);
                        let (s, e) = h.range_in(b, len);
                        off >= s && off < e
                    }
                })
                .cloned()
        };
        if let Some(h) = hit {
            self.selection_popover(
                Selection {
                    start: (h.block, h.start),
                    end: (h.last_block(), h.end),
                },
                Some(h.id.clone()),
            );
        }
    }

    /// Text of the current chapter between two (block, UTF-16 offset) positions, paragraphs joined by spaces.
    fn text_between(&self, s: (i32, i32), e: (i32, i32)) -> String {
        let blocks = &self.chapter().blocks;
        let mut out = String::new();
        for b in s.0..=e.0 {
            let Some(bl) = blocks.get(b as usize) else {
                break;
            };
            if bl.kind == IMAGE {
                continue;
            }
            let from = if b == s.0 {
                utf16_to_char(&bl.text, s.1)
            } else {
                0
            } as usize;
            let to = if b == e.0 {
                utf16_to_char(&bl.text, e.1) as usize
            } else {
                bl.text.chars().count()
            };
            if !out.is_empty() {
                out.push(' ');
            }
            out.extend(bl.text.chars().skip(from).take(to.saturating_sub(from)));
        }
        out.trim().to_string()
    }

    /// The selection / highlight bar: reactions, style, colours, note, post, copy and delete.
    fn selection_popover(self: &Rc<Self>, sel: Selection, existing: Option<String>) {
        if self.closed.get() || !self.view.is_mapped() {
            return;
        }
        if let Some(p) = self.popover.borrow_mut().take() {
            p.popdown();
        }
        let start_it = self.iter_at(sel.start.0, sel.start.1);
        let rect = self.view.iter_location(&start_it);
        let (x, y) =
            self.view
                .buffer_to_window_coords(gtk::TextWindowType::Widget, rect.x(), rect.y());
        let content = vbox(12);
        content.set_margin_top(6);
        content.set_margin_bottom(6);
        content.set_margin_start(6);
        content.set_margin_end(6);
        let pop = popover(&self.view, &content, true);
        pop.set_pointing_to(Some(&gdk::Rectangle::new(
            x,
            y,
            rect.width().max(2),
            rect.height(),
        )));
        pop.set_position(gtk::PositionType::Top);
        *self.popover.borrow_mut() = Some(pop.clone());
        let state = Rc::new(RefCell::new(existing));
        let sel_text = self.text_between(sel.start, sel.end);
        let fill: Rc<RefCell<Option<Rc<dyn Fn()>>>> = Rc::default();
        let render = {
            let (r, content, state, pop, fill) = (
                self.clone(),
                content.clone(),
                state.clone(),
                pop.clone(),
                fill.clone(),
            );
            let sel_text = sel_text.clone();
            Rc::new(move || {
                clear(&content);
                let existing: Option<Highlight> = state.borrow().as_ref().and_then(|id| {
                    r.app
                        .data()
                        .highlights
                        .iter()
                        .find(|h| h.id == *id)
                        .cloned()
                });
                let s = r.settings();
                if let Some(h) = &existing {
                    let row = hbox(4);
                    row.set_halign(gtk::Align::Center);
                    for e in REACTION_EMOJIS {
                        let b = gtk::Button::with_label(e);
                        b.add_css_class("emoji-button");
                        if h.reaction.as_deref() == Some(e) {
                            b.add_css_class("on");
                        }
                        let (r2, id, f) = (r.clone(), h.id.clone(), fill.clone());
                        b.connect_clicked(move |_| {
                            r2.app.update(|d| d.react_highlight(&id, e));
                            if let Some(f) = f.borrow().clone() {
                                f();
                            }
                        });
                        row.append(&b);
                    }
                    content.append(&row);
                }
                let row = hbox(8);
                let style = existing
                    .as_ref()
                    .map(|h| h.style)
                    .unwrap_or(s.highlight_style);
                let seg = hbox(0);
                seg.add_css_class("segment");
                for (st, ic, tip) in [
                    (0, "border-color", "Marker"),
                    (1, "format-underlined", "Underline"),
                ] {
                    let b = gtk::Button::new();
                    b.set_child(Some(&icon(ic, 18)));
                    b.set_tooltip_text(Some(tip));
                    if st == style {
                        b.add_css_class("accent");
                    }
                    let (r2, state2, f) = (r.clone(), state.clone(), fill.clone());
                    b.connect_clicked(move |_| {
                        let id = state2.borrow().clone();
                        r2.app
                            .update(|d| match id.as_ref().and_then(|id| d.highlight_mut(id)) {
                                Some(h) => h.style = st,
                                None => d.settings.highlight_style = st,
                            });
                        if let Some(f) = f.borrow().clone() {
                            f();
                        }
                    });
                    seg.append(&b);
                }
                row.append(&seg);
                for (i, name) in ["Yellow", "Green", "Blue", "Pink", "Purple"]
                    .iter()
                    .enumerate()
                {
                    let b = gtk::Button::new();
                    b.set_tooltip_text(Some(name));
                    b.add_css_class("hl-swatch");
                    b.add_css_class(&format!("c{i}"));
                    b.set_valign(gtk::Align::Center);
                    if existing.as_ref().is_some_and(|h| h.color == i as i32) {
                        b.add_css_class("on");
                    }
                    let (r2, state2, f, text) =
                        (r.clone(), state.clone(), fill.clone(), sel_text.clone());
                    b.connect_clicked(move |_| {
                        let id = state2.borrow().clone();
                        match id {
                            Some(id) => r2.app.update(|d| {
                                if let Some(h) = d.highlight_mut(&id) {
                                    h.color = i as i32;
                                }
                            }),
                            // The bar stays open on the new highlight so a reaction or note can follow.
                            None => {
                                *state2.borrow_mut() =
                                    Some(r2.add_highlight(sel, &text, i as i32, false))
                            }
                        }
                        if let Some(f) = f.borrow().clone() {
                            f();
                        }
                    });
                    row.append(&b);
                }
                if let Some(h) = &existing {
                    let del = gtk::Button::new();
                    del.add_css_class("flat");
                    del.add_css_class("destructive");
                    del.set_child(Some(&icon("delete-outline", 22)));
                    del.set_tooltip_text(Some("Delete highlight"));
                    let (r2, id, p) = (r.clone(), h.id.clone(), pop.clone());
                    del.connect_clicked(move |_| {
                        r2.app.update(|d| d.delete_highlight(&id));
                        p.popdown();
                    });
                    row.append(&del);
                }
                content.append(&row);
                let actions = hbox(0);
                actions.set_homogeneous(true);
                let quote = existing
                    .as_ref()
                    .map(|h| h.text.clone())
                    .unwrap_or_else(|| sel_text.clone());
                for (ic, name) in [
                    ("edit-note", "Note"),
                    ("format-quote", "Post"),
                    ("content-copy", "Copy"),
                    ("ios-share", "Quote"),
                ] {
                    let b = gtk::Button::new();
                    b.add_css_class("flat");
                    let v = vbox(2);
                    v.append(&icon(ic, 20));
                    let l = label(name, &["small"]);
                    l.set_xalign(0.5);
                    v.append(&l);
                    b.set_child(Some(&v));
                    let (r2, state2, p, q) = (r.clone(), state.clone(), pop.clone(), quote.clone());
                    let text = sel_text.clone();
                    b.connect_clicked(move |_| {
                        let id = state2.borrow().clone();
                        p.popdown();
                        match name {
                            "Note" => {
                                let id =
                                    id.unwrap_or_else(|| r2.add_highlight(sel, &text, 0, false));
                                note_sheet(&r2, &id);
                            }
                            "Post" => match id {
                                Some(id) => {
                                    let title = r2.chapter().title.clone();
                                    r2.app.update(|d| d.post_from_highlight(&id, &title));
                                    r2.app.toast("Post added to your Feed");
                                }
                                None => {
                                    r2.add_highlight(sel, &text, 0, true);
                                    r2.app.toast("Highlighted and posted to your Feed");
                                }
                            },
                            "Copy" => {
                                r2.root.clipboard().set_text(&q);
                                r2.app.toast("Copied");
                            }
                            _ => {
                                let (title, author) = r2
                                    .app
                                    .data()
                                    .book(&r2.book_id)
                                    .map(|b| (b.title.clone(), b.author.clone()))
                                    .unwrap_or_default();
                                r2.root
                                    .clipboard()
                                    .set_text(&format!("“{q}”\n— {title}, {author}"));
                                r2.app.toast("Quote copied with its source");
                            }
                        }
                    });
                    actions.append(&b);
                }
                content.append(&actions);
            }) as Rc<dyn Fn()>
        };
        *fill.borrow_mut() = Some(render.clone());
        render();
        let w = Rc::downgrade(self);
        pop.connect_closed(move |_| {
            with(&w, |r| {
                // Drop the text selection once the bar closes, as the phone does.
                let buf = r.buffer();
                let it = buf.iter_at_mark(&buf.get_insert());
                buf.select_range(&it, &it);
            })
        });
        pop.popup();
    }

    /// Saves a highlight over `sel` and returns its id.
    fn add_highlight(&self, sel: Selection, text: &str, color: i32, make_post: bool) -> String {
        let id = gbook_core::library::new_id();
        let h = Highlight {
            id: id.clone(),
            book_id: self.book_id.clone(),
            chapter: self.chapter.get() as i32,
            block: sel.start.0,
            start: sel.start.1,
            end: sel.end.1,
            end_block: if sel.end.0 == sel.start.0 {
                -1
            } else {
                sel.end.0
            },
            text: text.to_string(),
            color,
            style: self.settings().highlight_style,
            created_at: now_ms(),
            ..Default::default()
        };
        let title = self.chapter().title.clone();
        self.app.update(|d| d.add_highlight(h, &title, make_post));
        id
    }

    /// Highlights plus the focus tracker's marker and any highlight being painted by the wheel.
    fn refresh_marks(&self) {
        let mut marks = self.hl_marks.borrow().clone();
        let s = self.app.data().settings.clone();
        if let Some(sel) = self.painting() {
            let c = HIGHLIGHT_COLORS[s.tracker_color.max(0) as usize % HIGHLIGHT_COLORS.len()]
                .rgba(1.0);
            let mut fill = c;
            fill.set_alpha(0.38);
            marks.push(crate::pageview::Mark {
                start: self.iter_at(sel.start.0, sel.start.1).offset(),
                end: self.iter_at(sel.end.0, sel.end.1).offset(),
                fill: (s.highlight_style != 1).then_some(fill),
                underline: (s.highlight_style == 1).then_some(c),
                thin: false,
                reaction: None,
            });
        }
        if s.tracker
            && let Some((b, u)) = self.track.get()
            && let Some(&(a, e)) = self.units_of(b).get(u)
        {
            let accent = self.app.colors().accent;
            marks.push(crate::pageview::Mark {
                start: self.iter_at(b, a).offset(),
                end: self.iter_at(b, e).offset(),
                fill: Some(accent.rgba(0.2)),
                underline: Some(accent.rgba(1.0)),
                thin: true,
                reaction: None,
            });
        }
        let px = s.font_size.clamp(MIN_FONT, MAX_FONT);
        self.view.set_marks(marks, px);
    }

    /// The tracker's steps in paragraph `b`: its words or sentences.
    fn units_of(&self, b: i32) -> Vec<(i32, i32)> {
        let sentences = self.app.data().settings.tracker_unit == 1;
        self.units
            .borrow_mut()
            .entry(b)
            .or_insert_with(|| match self.chapter().blocks.get(b.max(0) as usize) {
                Some(bl) if bl.kind == PARAGRAPH => gbook_core::tracker::units(&bl.text, sentences),
                _ => Vec::new(),
            })
            .clone()
    }

    /// Top and bottom (view coordinates) of the line where unit `u` of paragraph `b` starts.
    fn unit_line(&self, b: i32, u: usize) -> Option<(f64, f64)> {
        let &(a, _) = self.units_of(b).get(u)?;
        let r = self.view.iter_location(&self.iter_at(b, a));
        let top = self.to_widget_y(r.y());
        Some((top, top + r.height() as f64))
    }

    /// Whether stepping should go on from (b, u) rather than restart at the top of the page.
    fn track_on_page(&self, b: i32, u: usize) -> bool {
        let h = self.scroller.height() as f64;
        match self.unit_line(b, u) {
            Some((top, bot)) => bot > TOP_PAD as f64 - 2.0 && top < h - BOTTOM_PAD as f64 + 2.0,
            None => false,
        }
    }

    fn first_unit_on_page(&self) -> Option<(i32, usize)> {
        let (it, _) = self.view.line_at_y(self.to_buffer_y(TOP_PAD as f64));
        let (first, _) = self.block_pos(it.offset());
        let n = self.chapter().blocks.len() as i32;
        for b in first..n.min(first + 40) {
            for u in 0..self.units_of(b).len() {
                if let Some((top, _)) = self.unit_line(b, u)
                    && top >= TOP_PAD as f64 - 0.5
                {
                    return Some((b, u));
                }
            }
        }
        None
    }

    /// Moves the tracker one word or sentence; keeps it on screen by turning or scrolling the page.
    pub fn step_track(self: &Rc<Self>, forward: bool) -> bool {
        self.touch();
        let next = match self.track.get().filter(|&(b, u)| self.track_on_page(b, u)) {
            None => self.first_unit_on_page(),
            Some((b, u)) => {
                let n = self.chapter().blocks.len() as i32;
                if forward {
                    if u + 1 < self.units_of(b).len() {
                        Some((b, u + 1))
                    } else {
                        (b + 1..n)
                            .find(|&x| !self.units_of(x).is_empty())
                            .map(|x| (x, 0))
                    }
                } else if u > 0 {
                    Some((b, u - 1))
                } else {
                    (0..b)
                        .rev()
                        .find(|&x| !self.units_of(x).is_empty())
                        .map(|x| (x, self.units_of(x).len() - 1))
                }
            }
        };
        let Some((b, u)) = next else { return false };
        self.track.set(Some((b, u)));
        self.refresh_marks();
        // Keep the marker in view.
        if let Some((top, bot)) = self.unit_line(b, u) {
            let h = self.scroller.height() as f64;
            let paged = self.app.data().settings.page_mode == 1;
            let below = bot > h - BOTTOM_PAD as f64;
            let above = top < TOP_PAD as f64;
            if paged && (below || above) {
                self.turn(below);
            } else if below || above {
                let adj = self.scroller.vadjustment();
                animate_to(
                    &self.scroller,
                    (adj.value() + top - (TOP_PAD as f64 + self.page_height() * 0.3)).max(0.0),
                );
            }
        }
        true
    }

    pub fn dev_paint(self: &Rc<Self>, steps: usize) {
        self.start_paint();
        for _ in 0..steps {
            self.step_track(true);
        }
        self.finish_paint();
    }

    /// The text a highlighting turn of the wheel has covered so far.
    fn painting(&self) -> Option<Selection> {
        let (a, b) = (self.paint_from.get()?, self.track.get()?);
        let (lo, hi) = if b < a { (b, a) } else { (a, b) };
        let l = *self.units_of(lo.0).get(lo.1)?;
        let h = *self.units_of(hi.0).get(hi.1)?;
        Some(Selection {
            start: (lo.0, l.0),
            end: (hi.0, h.1),
        })
    }

    /// A turn of the wheel begins: with a highlight colour chosen, highlighting starts at the marker.
    fn start_paint(self: &Rc<Self>) {
        if let Some(w) = self.wheel.borrow().as_ref() {
            w.set_held(true);
        }
        if self.app.data().settings.tracker_color < 0 {
            return;
        }
        if self
            .track
            .get()
            .is_none_or(|(b, u)| !self.track_on_page(b, u))
        {
            self.track.set(self.first_unit_on_page());
        }
        self.paint_from.set(self.track.get());
        self.refresh_marks();
    }

    /// The turn is over: save what it highlighted.
    fn finish_paint(self: &Rc<Self>) {
        if let Some(w) = self.wheel.borrow().as_ref() {
            w.set_held(false);
        }
        let sel = self.painting();
        self.paint_from.set(None);
        let color = self.app.data().settings.tracker_color;
        match sel {
            Some(sel) if color >= 0 => {
                let text = self.text_between(sel.start, sel.end);
                if !text.is_empty() {
                    self.add_highlight(sel, &text, color, false);
                }
            }
            _ => self.refresh_marks(),
        }
    }

    /// A paragraph from the page, so text settings can be judged on the book itself.
    pub fn preview_text(&self) -> String {
        let (b, _) = self.top_block();
        let ch = self.chapter();
        ch.blocks
            .iter()
            .skip(b.max(0) as usize)
            .chain(
                self.content
                    .chapters
                    .iter()
                    .skip(self.chapter.get() + 1)
                    .flat_map(|c| c.blocks.iter()),
            )
            .find(|x| x.kind == PARAGRAPH && x.text.chars().count() > 60)
            .map(|x| x.text.chars().take(240).collect())
            .unwrap_or_else(|| {
                "Do not act as if thou wert going to live ten thousand years.".into()
            })
    }

    pub fn progress_percent(&self) -> i32 {
        (self.progress() * 100.0) as i32
    }

    pub fn highlight_count(&self) -> usize {
        self.app
            .data()
            .highlights
            .iter()
            .filter(|h| h.book_id == self.book_id)
            .count()
    }
}

#[derive(Clone, Copy)]
struct Selection {
    start: (i32, i32),
    end: (i32, i32),
}

/// Smoothly scrolls `sc` to `target` over ~180 ms.
fn animate_to(sc: &gtk::ScrolledWindow, target: f64) {
    let adj = sc.vadjustment();
    let from = adj.value();
    let start = Instant::now();
    sc.add_tick_callback(move |_, _| {
        let t = (start.elapsed().as_secs_f64() / 0.18).min(1.0);
        let e = 1.0 - (1.0 - t).powi(3);
        adj.set_value(from + (target - from) * e);
        if t >= 1.0 {
            glib::ControlFlow::Break
        } else {
            glib::ControlFlow::Continue
        }
    });
}

/// Contents (chapters) and the book's highlights.
pub fn contents_sheet(r: &Rc<Reader>, tab: usize) {
    let col = vbox(4);
    col.set_margin_start(12);
    col.set_margin_end(12);
    col.set_margin_bottom(16);
    let tabs = hbox(8);
    tabs.set_margin_start(12);
    tabs.set_margin_bottom(8);
    let list = vbox(2);
    col.append(&tabs);
    col.append(&list);
    let close: Rc<RefCell<Option<sheet::Close>>> = Rc::default();
    let fill: Rc<RefCell<Option<Rc<dyn Fn(usize)>>>> = Rc::default();
    let render = {
        let (r, list, tabs, close, fill) = (
            r.clone(),
            list.clone(),
            tabs.clone(),
            close.clone(),
            fill.clone(),
        );
        Rc::new(move |tab: usize| {
            clear(&tabs);
            for (i, name) in ["Contents", "Highlights"].iter().enumerate() {
                let b = gtk::Button::with_label(name);
                b.add_css_class("chip");
                b.add_css_class("solid");
                if i == tab {
                    b.add_css_class("on");
                }
                let f = fill.clone();
                b.connect_clicked(move |_| {
                    if let Some(f) = f.borrow().clone() {
                        f(i);
                    }
                });
                tabs.append(&b);
            }
            clear(&list);
            if tab == 0 {
                for (i, c) in r.content.chapters.iter().enumerate() {
                    let b = gtk::Button::new();
                    b.add_css_class("menu-row");
                    let on = i == r.chapter.get();
                    if on {
                        b.add_css_class("on");
                    }
                    let h = hbox(12);
                    let l = Marquee::new(
                        &c.title,
                        if on { &["accent", "semibold"] } else { &[] },
                        Reveal::Hover,
                        -1,
                    );
                    l.widget.set_hexpand(true);
                    h.append(&l.widget);
                    h.append(&label(&(i + 1).to_string(), &["text2", "caption"]));
                    b.set_child(Some(&h));
                    let (r2, c2) = (r.clone(), close.clone());
                    b.connect_clicked(move |_| {
                        if let Some(c) = c2.borrow().as_ref() {
                            c();
                        }
                        r2.go_to(i, 0);
                    });
                    list.append(&b);
                }
            } else {
                let d = r.app.data();
                let mut hs: Vec<Highlight> = d
                    .highlights
                    .iter()
                    .filter(|h| h.book_id == r.book_id)
                    .cloned()
                    .collect();
                drop(d);
                hs.sort_by_key(|h| (h.chapter, h.block, h.start));
                if hs.is_empty() {
                    let l = label(
                        "No highlights yet. Select text to highlight it.",
                        &["secondary"],
                    );
                    l.set_margin_start(16);
                    l.set_margin_top(12);
                    list.append(&l);
                }
                for h in hs {
                    let b = gtk::Button::new();
                    b.add_css_class("menu-row");
                    let row = hbox(12);
                    let stripe = vbox(0);
                    stripe.set_size_request(4, -1);
                    stripe.add_css_class(&format!("hl-stripe-{}", h.color.rem_euclid(5)));
                    row.append(&stripe);
                    let v = vbox(2);
                    v.set_hexpand(true);
                    let ct = r
                        .content
                        .chapters
                        .get(h.chapter as usize)
                        .map(|c| c.title.as_str())
                        .unwrap_or("");
                    v.append(&Marquee::new(ct, &["eyebrow"], Reveal::Hover, -1).widget);
                    v.append(&wrap_label(&h.text, &["quote"], 3));
                    if let Some(n) = h.note.as_ref().filter(|n| !n.is_empty()) {
                        v.append(&wrap_label(n, &["caption", "text2"], 2));
                    }
                    row.append(&v);
                    if let Some(e) = &h.reaction {
                        row.append(&label(e, &[]));
                    }
                    b.set_child(Some(&row));
                    let (r2, c2) = (r.clone(), close.clone());
                    b.connect_clicked(move |_| {
                        if let Some(c) = c2.borrow().as_ref() {
                            c();
                        }
                        r2.go_to(h.chapter as usize, h.block);
                    });
                    list.append(&b);
                }
            }
        }) as Rc<dyn Fn(usize)>
    };
    *fill.borrow_mut() = Some(render.clone());
    render(tab);
    let title = r
        .app
        .data()
        .book(&r.book_id)
        .map(|b| b.title.clone())
        .unwrap_or_default();
    *close.borrow_mut() = Some(sheet::present(&r.app, Some(&title), &col, 520));
}

/// Finds a word or phrase anywhere in the book; picking a result opens it with every match marked.
pub fn search_sheet(r: &Rc<Reader>) {
    let col = vbox(10);
    col.set_margin_start(16);
    col.set_margin_end(16);
    col.set_margin_bottom(16);
    let entry = gtk::SearchEntry::new();
    entry.set_placeholder_text(Some("Search this book"));
    if let Some(t) = r.search_term.borrow().as_ref() {
        entry.set_text(t);
    }
    col.append(&entry);
    let count = label("", &["caption", "text2"]);
    count.set_margin_start(8);
    col.append(&count);
    let list = vbox(2);
    col.append(&list);
    let close: Rc<RefCell<Option<sheet::Close>>> = Rc::default();
    let (r2, list2, c2, count2) = (r.clone(), list.clone(), close.clone(), count.clone());
    entry.connect_search_changed(move |e| {
        clear(&list2);
        let term = e.text().trim().to_string();
        if term.chars().count() < 2 {
            count2.set_text("");
            return;
        }
        let lower = term.to_lowercase();
        let mut n = 0;
        'outer: for (ci, c) in r2.content.chapters.iter().enumerate() {
            for (bi, b) in c.blocks.iter().enumerate() {
                if b.kind == IMAGE {
                    continue;
                }
                let text = b.text.to_lowercase();
                let Some(at) = text.find(&lower) else {
                    continue;
                };
                n += 1;
                if n > 200 {
                    break 'outer;
                }
                // A snippet around the match.
                let pre: String = text[..at]
                    .chars()
                    .rev()
                    .take(50)
                    .collect::<Vec<_>>()
                    .into_iter()
                    .rev()
                    .collect();
                let start_chars = text[..at].chars().count() - pre.chars().count();
                let snippet: String = b.text.chars().skip(start_chars).take(160).collect();
                let btn = gtk::Button::new();
                btn.add_css_class("menu-row");
                let v = vbox(2);
                v.append(&Marquee::new(&c.title, &["eyebrow"], Reveal::Hover, -1).widget);
                let l = wrap_label(
                    &format!("{}{snippet}…", if start_chars > 0 { "…" } else { "" }),
                    &[],
                    3,
                );
                v.append(&l);
                btn.set_child(Some(&v));
                let (r3, c3, t) = (r2.clone(), c2.clone(), term.clone());
                btn.connect_clicked(move |_| {
                    if let Some(c) = c3.borrow().as_ref() {
                        c();
                    }
                    *r3.search_term.borrow_mut() = Some(t.clone());
                    r3.go_to(ci, bi as i32);
                    r3.apply_highlights();
                });
                list2.append(&btn);
            }
        }
        count2.set_text(&match n {
            0 => "No matches".to_string(),
            1 => "1 match".to_string(),
            n if n > 200 => "More than 200 matches".to_string(),
            n => format!("{n} matches"),
        });
    });
    *close.borrow_mut() = Some(sheet::present(&r.app, Some("Search"), &col, 520));
    let e = entry.clone();
    glib::idle_add_local_once(move || {
        e.grab_focus();
    });
}

fn note_sheet(r: &Rc<Reader>, id: &str) {
    let Some(h) = r.app.data().highlights.iter().find(|h| h.id == id).cloned() else {
        return;
    };
    let col = vbox(14);
    col.set_margin_start(24);
    col.set_margin_end(24);
    col.set_margin_bottom(24);
    col.append(&wrap_label(
        &format!("“{}”", h.text),
        &["quote", "text2"],
        4,
    ));
    let tv = gtk::TextView::new();
    tv.add_css_class("note");
    tv.set_wrap_mode(gtk::WrapMode::WordChar);
    tv.set_size_request(-1, 120);
    tv.buffer().set_text(h.note.as_deref().unwrap_or(""));
    col.append(&tv);
    let save = gtk::Button::with_label("Save Note");
    save.add_css_class("primary");
    save.set_halign(gtk::Align::End);
    col.append(&save);
    let close = sheet::present(&r.app, Some("Note"), &col, 460);
    let (a, id, buf) = (r.app.clone(), id.to_string(), tv.buffer());
    save.connect_clicked(move |_| {
        let (s, e) = buf.bounds();
        let note = buf.text(&s, &e, false).trim().to_string();
        a.update(|d| {
            if let Some(h) = d.highlight_mut(&id) {
                h.note = if note.is_empty() {
                    None
                } else {
                    Some(note.clone())
                };
            }
        });
        close();
    });
    glib::idle_add_local_once(move || {
        tv.grab_focus();
    });
}

// A texture drawn at a fixed size, so book images fit the column instead of showing at full resolution.
mod scaled_imp {
    use super::*;
    use gtk::subclass::prelude::*;

    #[derive(Default)]
    pub struct ScaledPaintable {
        pub texture: RefCell<Option<gdk::Texture>>,
        pub size: Cell<(i32, i32)>,
    }

    #[glib::object_subclass]
    impl ObjectSubclass for ScaledPaintable {
        const NAME: &'static str = "GBookScaledPaintable";
        type Type = super::ScaledPaintable;
        type Interfaces = (gdk::Paintable,);
    }

    impl ObjectImpl for ScaledPaintable {}

    impl gdk::subclass::paintable::PaintableImpl for ScaledPaintable {
        fn intrinsic_width(&self) -> i32 {
            self.size.get().0
        }
        fn intrinsic_height(&self) -> i32 {
            self.size.get().1
        }
        fn snapshot(&self, snapshot: &gdk::Snapshot, width: f64, height: f64) {
            if let Some(t) = self.texture.borrow().as_ref() {
                t.snapshot(snapshot, width, height);
            }
        }
    }
}

glib::wrapper! {
    pub struct ScaledPaintable(ObjectSubclass<scaled_imp::ScaledPaintable>) @implements gdk::Paintable;
}

impl ScaledPaintable {
    fn new(t: &gdk::Texture, w: i32, h: i32) -> Self {
        let p: ScaledPaintable = glib::Object::new();
        use gtk::subclass::prelude::ObjectSubclassIsExt;
        *p.imp().texture.borrow_mut() = Some(t.clone());
        p.imp().size.set((w.max(1), h.max(1)));
        p
    }
}
