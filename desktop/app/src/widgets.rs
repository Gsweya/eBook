//! Small building blocks shared by every screen: a max-width clamp, book covers, round icon buttons, labels.

use std::cell::{Cell, RefCell};

use gbook_core::Book;
use gtk::prelude::*;
use gtk::subclass::prelude::*;
use gtk::{gdk, glib, graphene, gsk, pango};

use crate::state::AppRc;
use crate::theme::{self, Rgb};

// ---------------------------------------------------------------------------------------------------------
// Clamp: centres its child and keeps it no wider than `max`.

mod clamp_imp {
    use super::*;

    #[derive(Default)]
    pub struct Clamp {
        pub max: Cell<i32>,
        pub child: RefCell<Option<gtk::Widget>>,
    }

    #[glib::object_subclass]
    impl ObjectSubclass for Clamp {
        const NAME: &'static str = "GBookClamp";
        type Type = super::Clamp;
        type ParentType = gtk::Widget;
    }

    impl ObjectImpl for Clamp {
        fn dispose(&self) {
            if let Some(c) = self.child.take() {
                c.unparent();
            }
        }
    }

    impl WidgetImpl for Clamp {
        fn request_mode(&self) -> gtk::SizeRequestMode {
            gtk::SizeRequestMode::HeightForWidth
        }

        fn measure(&self, orientation: gtk::Orientation, for_size: i32) -> (i32, i32, i32, i32) {
            let Some(c) = self.child.borrow().clone() else {
                return (0, 0, -1, -1);
            };
            let max = self.max.get();
            if orientation == gtk::Orientation::Horizontal {
                let (min, nat, _, _) = c.measure(orientation, for_size);
                (min, nat.max(max).max(min), -1, -1)
            } else {
                let w = if for_size < 0 { -1 } else { for_size.min(max) };
                let (min, nat, _, _) = c.measure(orientation, w);
                (min, nat, -1, -1)
            }
        }

        fn size_allocate(&self, width: i32, height: i32, baseline: i32) {
            if let Some(c) = self.child.borrow().as_ref() {
                let (min, _, _, _) = c.measure(gtk::Orientation::Horizontal, -1);
                let w = width.min(self.max.get()).max(min.min(width));
                let x = (width - w) / 2;
                c.size_allocate(&gtk::Allocation::new(x, 0, w, height), baseline);
            }
        }
    }
}

glib::wrapper! {
    pub struct Clamp(ObjectSubclass<clamp_imp::Clamp>) @extends gtk::Widget, @implements gtk::Accessible, gtk::Buildable, gtk::ConstraintTarget;
}

impl Clamp {
    pub fn new(child: &impl IsA<gtk::Widget>, max: i32) -> Clamp {
        let c: Clamp = glib::Object::new();
        c.imp().max.set(max);
        child.set_parent(&c);
        *c.imp().child.borrow_mut() = Some(child.clone().upcast());
        c.set_hexpand(true);
        c
    }

    pub fn set_max(&self, max: i32) {
        if self.imp().max.replace(max) != max {
            self.queue_resize();
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Cover: the cover image cropped to a book's proportions, or a generated cover, with a spine hinge.

mod cover_imp {
    use super::*;

    pub struct Cover {
        pub width: Cell<i32>,
        pub height: Cell<i32>,
        pub texture: RefCell<Option<gdk::Texture>>,
        pub color: Cell<Rgb>,
        pub title: RefCell<String>,
        pub author: RefCell<String>,
        pub radius: Cell<f32>,
        /// The cover image is still loading: show just its colour, not a generated cover.
        pub loading: Cell<bool>,
    }

    impl Default for Cover {
        fn default() -> Self {
            Cover {
                width: Cell::new(120),
                height: Cell::new(182),
                texture: RefCell::new(None),
                color: Cell::new(Rgb::hex(0x8F3D26)),
                title: RefCell::new(String::new()),
                author: RefCell::new(String::new()),
                radius: Cell::new(5.0),
                loading: Cell::new(false),
            }
        }
    }

    #[glib::object_subclass]
    impl ObjectSubclass for Cover {
        const NAME: &'static str = "GBookCover";
        type Type = super::Cover;
        type ParentType = gtk::Widget;
    }

    impl ObjectImpl for Cover {}

    impl WidgetImpl for Cover {
        fn measure(&self, orientation: gtk::Orientation, _for_size: i32) -> (i32, i32, i32, i32) {
            let v = if orientation == gtk::Orientation::Horizontal {
                self.width.get()
            } else {
                self.height.get()
            };
            (v, v, -1, -1)
        }

        fn snapshot(&self, s: &gtk::Snapshot) {
            let w = self.obj().width() as f32;
            let h = self.obj().height() as f32;
            let r = self.radius.get();
            let rect = graphene::Rect::new(0.0, 0.0, w, h);
            let big = graphene::Size::new(r, r);
            let small = graphene::Size::new(2.0, 2.0);
            s.push_rounded_clip(&gsk::RoundedRect::new(rect, small, big, big, small));
            if let Some(t) = self.texture.borrow().as_ref() {
                // Crop to fill, like ContentScale.Crop.
                let (tw, th) = (t.width() as f32, t.height() as f32);
                let scale = (w / tw).max(h / th);
                let (dw, dh) = (tw * scale, th * scale);
                s.append_texture(
                    t,
                    &graphene::Rect::new((w - dw) / 2.0, (h - dh) / 2.0, dw, dh),
                );
            } else {
                let c = self.color.get();
                let top = c.lerp(theme::WHITE, 0.12);
                s.append_linear_gradient(
                    &rect,
                    &graphene::Point::new(0.0, 0.0),
                    &graphene::Point::new(0.0, h),
                    &[
                        gsk::ColorStop::new(0.0, top.rgba(1.0)),
                        gsk::ColorStop::new(1.0, c.rgba(1.0)),
                    ],
                );
            }
            if self.texture.borrow().is_none() && !self.loading.get() {
                let obj = self.obj();
                let pad = (w * 0.1).max(6.0);
                let tl = obj.create_pango_layout(Some(&self.title.borrow()));
                let mut fd =
                    pango::FontDescription::from_string(&format!("{} Bold", theme::DISPLAY));
                fd.set_absolute_size((w * 0.13).clamp(9.0, 22.0) as f64 * pango::SCALE as f64);
                tl.set_font_description(Some(&fd));
                tl.set_width(((w - 2.0 * pad) * pango::SCALE as f32) as i32);
                tl.set_alignment(pango::Alignment::Center);
                tl.set_wrap(pango::WrapMode::WordChar);
                tl.set_height(-5);
                tl.set_ellipsize(pango::EllipsizeMode::End);
                let al = obj.create_pango_layout(Some(&self.author.borrow()));
                let mut fa = pango::FontDescription::from_string(theme::SANS);
                fa.set_absolute_size((w * 0.085).clamp(7.0, 14.0) as f64 * pango::SCALE as f64);
                al.set_font_description(Some(&fa));
                al.set_width(((w - 2.0 * pad) * pango::SCALE as f32) as i32);
                al.set_alignment(pango::Alignment::Center);
                al.set_ellipsize(pango::EllipsizeMode::End);
                let th = tl.pixel_size().1 as f32;
                let ah = al.pixel_size().1 as f32;
                let y = ((h - th - ah - 8.0) / 2.0).max(pad);
                s.save();
                s.translate(&graphene::Point::new(pad, y));
                s.append_layout(&tl, &gdk::RGBA::WHITE);
                s.translate(&graphene::Point::new(0.0, th + 8.0));
                s.append_layout(&al, &gdk::RGBA::new(1.0, 1.0, 1.0, 0.8));
                s.restore();
            }
            // Spine hinge
            let spine = graphene::Rect::new(0.0, 0.0, (w * 0.05).clamp(3.0, 7.0), h);
            s.append_linear_gradient(
                &spine,
                &graphene::Point::new(0.0, 0.0),
                &graphene::Point::new(spine.width(), 0.0),
                &[
                    gsk::ColorStop::new(0.0, gdk::RGBA::new(0.0, 0.0, 0.0, 0.22)),
                    gsk::ColorStop::new(0.5, gdk::RGBA::new(1.0, 1.0, 1.0, 0.10)),
                    gsk::ColorStop::new(1.0, gdk::RGBA::new(1.0, 1.0, 1.0, 0.0)),
                ],
            );
            s.pop();
        }
    }
}

glib::wrapper! {
    pub struct Cover(ObjectSubclass<cover_imp::Cover>) @extends gtk::Widget, @implements gtk::Accessible, gtk::Buildable, gtk::ConstraintTarget;
}

/// Book covers are 2:3-ish, like the phone's 0.66 aspect ratio.
pub const COVER_ASPECT: f32 = 0.66;

impl Cover {
    /// A cover `width` pixels wide for `book`; the image loads in the background.
    pub fn new(app: &AppRc, book: &Book, width: i32) -> Cover {
        let c: Cover = glib::Object::new();
        c.add_css_class("cover");
        if width < 70 {
            c.add_css_class("small");
            c.imp().radius.set(3.0);
        }
        c.set_size(width, (width as f32 / COVER_ASPECT).round() as i32);
        c.set_book(app, book);
        c
    }

    pub fn set_size(&self, w: i32, h: i32) {
        self.imp().width.set(w);
        self.imp().height.set(h);
        self.queue_resize();
    }

    pub fn set_book(&self, app: &AppRc, book: &Book) {
        let imp = self.imp();
        imp.color.set(Rgb::from_argb(book.cover_color));
        *imp.title.borrow_mut() = book.title.clone();
        *imp.author.borrow_mut() = book.author.clone();
        *imp.texture.borrow_mut() = None;
        imp.loading.set(false);
        if let Some(p) = app.cover_path(&book.id) {
            imp.loading.set(true);
            // Decoded at the size shown (times the screen scale), never larger.
            let px = imp.width.get() * app.ui_scale();
            let weak = self.downgrade();
            if let Some(t) = app.texture(&p, px, move |t| {
                if let Some(c) = weak.upgrade() {
                    *c.imp().texture.borrow_mut() = Some(t);
                    c.imp().loading.set(false);
                    c.queue_draw();
                }
            }) {
                *imp.texture.borrow_mut() = Some(t);
            }
        }
        self.queue_draw();
    }
}

// ---------------------------------------------------------------------------------------------------------
// Helpers

pub fn icon(name: &str, size: i32) -> gtk::Image {
    let i = gtk::Image::from_icon_name(&format!("gbook-{name}-symbolic"));
    i.set_pixel_size(size);
    i
}

/// Round floating button holding an icon (the phone's `IconCircle`).
pub fn circle_button(name: &str, tooltip: &str) -> gtk::Button {
    let b = gtk::Button::new();
    b.set_child(Some(&icon(name, 22)));
    b.add_css_class("circle");
    b.set_tooltip_text(Some(tooltip));
    b.set_valign(gtk::Align::Center);
    b
}

pub fn label(text: &str, classes: &[&str]) -> gtk::Label {
    let l = gtk::Label::new(Some(text));
    l.set_xalign(0.0);
    for c in classes {
        l.add_css_class(c);
    }
    l
}

/// A label that wraps and ellipsizes after `lines` lines.
pub fn wrap_label(text: &str, classes: &[&str], lines: i32) -> gtk::Label {
    let l = label(text, classes);
    l.set_wrap(true);
    l.set_wrap_mode(pango::WrapMode::WordChar);
    l.set_lines(lines);
    l.set_ellipsize(pango::EllipsizeMode::End);
    l
}

pub fn vbox(spacing: i32) -> gtk::Box {
    gtk::Box::new(gtk::Orientation::Vertical, spacing)
}

pub fn hbox(spacing: i32) -> gtk::Box {
    gtk::Box::new(gtk::Orientation::Horizontal, spacing)
}

pub fn spacer() -> gtk::Box {
    let b = hbox(0);
    b.set_hexpand(true);
    b
}

pub fn clear(b: &gtk::Box) {
    while let Some(c) = b.first_child() {
        b.remove(&c);
    }
}

/// Thin rounded progress bar filled with the accent.
pub fn progress(fraction: f32, thick: bool) -> gtk::Widget {
    let bar = gtk::ProgressBar::new();
    bar.add_css_class("gbar");
    if thick {
        bar.add_css_class("thick");
    }
    bar.set_fraction(fraction.clamp(0.0, 1.0) as f64);
    bar.set_hexpand(true);
    bar.set_valign(gtk::Align::Center);
    bar.upcast()
}

/// Circular profile picture, or initials on the accent when there is none.
pub fn avatar(app: &AppRc, size: i32) -> gtk::Widget {
    let path = app.paths.avatar();
    let (name, version) = {
        let s = &app.data().settings;
        (s.profile_name.clone(), s.avatar_version)
    };
    let frame = gtk::Overlay::new();
    frame.add_css_class("avatar");
    frame.set_size_request(size, size);
    frame.set_overflow(gtk::Overflow::Hidden);
    frame.set_valign(gtk::Align::Center);
    frame.set_halign(gtk::Align::Start);
    let initials: String = name
        .split_whitespace()
        .filter_map(|w| w.chars().next())
        .take(2)
        .collect::<String>()
        .to_uppercase();
    let l = gtk::Label::new(Some(if initials.is_empty() { "" } else { &initials }));
    if initials.is_empty() {
        frame.set_child(Some(&icon("person", size / 2)));
    } else {
        l.set_attributes(Some(&{
            let a = pango::AttrList::new();
            a.insert(pango::AttrSize::new_size_absolute(
                size * pango::SCALE * 2 / 5,
            ));
            a
        }));
        frame.set_child(Some(&l));
    }
    if version != 0 && path.exists() {
        let pic = gtk::Picture::new();
        pic.set_content_fit(gtk::ContentFit::Cover);
        pic.set_can_shrink(true);
        frame.add_overlay(&pic);
        let p2 = pic.clone();
        app.forget_textures(&path);
        if let Some(t) = app.texture(&path, size * app.ui_scale(), move |t| {
            p2.set_paintable(Some(&t))
        }) {
            pic.set_paintable(Some(&t));
        }
    }
    frame.upcast()
}

thread_local! {
    /// The most recently opened popover, for the dev tools' snapshots.
    pub static LAST_POPOVER: RefCell<Option<glib::WeakRef<gtk::Popover>>> = const { RefCell::new(None) };
}

/// A popover styled like the phone's menus and sheets, attached to `parent`.
pub fn popover(
    parent: &impl IsA<gtk::Widget>,
    child: &impl IsA<gtk::Widget>,
    sheet: bool,
) -> gtk::Popover {
    let p = gtk::Popover::new();
    LAST_POPOVER.with(|l| *l.borrow_mut() = Some(p.downgrade()));
    p.add_css_class(if sheet { "sheet" } else { "menu" });
    p.set_has_arrow(false);
    p.set_child(Some(child));
    p.set_parent(parent);
    let pw = p.downgrade();
    p.connect_closed(move |_| {
        // Unparent after closing so popovers made on the fly don't pile up.
        if let Some(p) = pw.upgrade() {
            glib::idle_add_local_once(move || p.unparent());
        }
    });
    p
}

/// The top bar of every screen: draggable like a title bar, with the window controls in the corner. With `max`,
/// With `column` (width, side margin), the start and end widgets line up with a centred content column of that width
/// whose content is inset by that margin.
pub fn top_bar(
    start: &impl IsA<gtk::Widget>,
    center: Option<&gtk::Widget>,
    end: &impl IsA<gtk::Widget>,
    clear: bool,
    column: Option<(i32, i32)>,
) -> gtk::WindowHandle {
    let outer = hbox(0);
    outer.add_css_class("topbar");
    if clear {
        outer.add_css_class("clear");
    }
    let row = gtk::CenterBox::new();
    // A long title gives way before the buttons do (it scrolls to show the rest).
    row.set_shrink_center_last(false);
    row.set_start_widget(Some(start));
    row.set_center_widget(center);
    row.set_end_widget(Some(end));
    let handle = gtk::WindowHandle::new();
    match column {
        // Centred on the window like the content below.
        Some((m, margin)) => {
            outer.add_css_class("aligned");
            row.set_margin_start(margin);
            row.set_margin_end(margin);
            outer.append(&Clamp::new(&row, m));
        }
        None => {
            row.set_hexpand(true);
            outer.append(&row);
        }
    }
    handle.set_child(Some(&outer));
    handle.set_valign(gtk::Align::Start);
    handle
}

/// Opens `uri` in the user's browser.
pub fn open_uri(app: &AppRc, uri: &str) {
    let l = gtk::UriLauncher::new(uri);
    l.launch(app.window().as_ref(), gtk::gio::Cancellable::NONE, |_| {});
}

// ---------------------------------------------------------------------------------------------------------
// Marquee: one line of text shown in full; when it's wider than its space it scrolls to reveal the rest.

/// When a marquee scrolls.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Reveal {
    /// Twice whenever it appears or its text changes, and again on hover.
    Auto,
    /// Only while the pointer is over it.
    Hover,
}

#[derive(Clone)]
pub struct Marquee {
    pub widget: gtk::ScrolledWindow,
    label: gtk::Label,
    run: std::rc::Rc<MarqueeRun>,
}

#[derive(Default)]
struct MarqueeRun {
    /// When the current pass started, while animating.
    started: Cell<Option<std::time::Instant>>,
    /// Passes left; each goes to the end and back.
    passes: Cell<u32>,
    tick: RefCell<Option<gtk::TickCallbackId>>,
}

/// Scroll speed (px/s) and the pause at each end of a pass.
const MARQUEE_SPEED: f64 = 45.0;
const MARQUEE_PAUSE: f64 = 1.2;

impl Marquee {
    /// A marquee at most `max_width` pixels wide (-1 for as wide as its place).
    pub fn new(text: &str, classes: &[&str], reveal: Reveal, max_width: i32) -> Marquee {
        let l = label(text, classes);
        l.set_single_line_mode(true);
        let sw = gtk::ScrolledWindow::new();
        sw.set_policy(gtk::PolicyType::External, gtk::PolicyType::Never);
        sw.set_propagate_natural_width(true);
        sw.set_propagate_natural_height(true);
        sw.set_max_content_width(max_width);
        sw.set_child(Some(&l));
        sw.set_can_target(true);
        let m = Marquee {
            widget: sw.clone(),
            label: l,
            run: Default::default(),
        };
        let enter = gtk::EventControllerMotion::new();
        let mm = m.clone();
        enter.connect_enter(move |_, _, _| mm.start(u32::MAX));
        let mm = m.clone();
        enter.connect_leave(move |_| {
            if reveal == Reveal::Hover {
                mm.stop();
            } else {
                // Finish the current pass, then rest.
                mm.run.passes.set(mm.run.passes.get().min(1));
            }
        });
        sw.add_controller(enter);
        if reveal == Reveal::Auto {
            let mm = m.clone();
            sw.connect_map(move |_| mm.start_soon());
        }
        m
    }

    pub fn set_text(&self, text: &str) {
        if self.label.text() != text {
            self.label.set_text(text);
            self.stop();
            if self.widget.is_mapped() {
                self.start_soon();
            }
        }
    }

    /// Two passes once the layout has settled.
    fn start_soon(&self) {
        let m = self.clone();
        glib::timeout_add_local_once(std::time::Duration::from_millis(500), move || m.start(2));
    }

    fn start(&self, passes: u32) {
        let adj = self.widget.hadjustment();
        if adj.upper() - adj.page_size() <= 1.0 {
            return;
        }
        self.run.passes.set(passes);
        if self.run.tick.borrow().is_some() {
            return;
        }
        self.run.started.set(Some(std::time::Instant::now()));
        let run = self.run.clone();
        let id = self.widget.add_tick_callback(move |sw, _| {
            let adj = sw.hadjustment();
            let over = adj.upper() - adj.page_size();
            let Some(start) = run.started.get() else {
                return glib::ControlFlow::Break;
            };
            if over <= 1.0 {
                adj.set_value(0.0);
                run.tick.borrow_mut().take();
                return glib::ControlFlow::Break;
            }
            let travel = over / MARQUEE_SPEED;
            let t = start.elapsed().as_secs_f64();
            // Pause, scroll to the end, pause, scroll back twice as fast.
            let x = if t < MARQUEE_PAUSE {
                0.0
            } else if t < MARQUEE_PAUSE + travel {
                (t - MARQUEE_PAUSE) * MARQUEE_SPEED
            } else if t < 2.0 * MARQUEE_PAUSE + travel {
                over
            } else if t < 2.0 * MARQUEE_PAUSE + 1.5 * travel {
                over - (t - 2.0 * MARQUEE_PAUSE - travel) * MARQUEE_SPEED * 2.0
            } else {
                let left = run.passes.get().saturating_sub(1);
                run.passes.set(left);
                if left == 0 {
                    adj.set_value(0.0);
                    run.started.set(None);
                    run.tick.borrow_mut().take();
                    return glib::ControlFlow::Break;
                }
                run.started.set(Some(std::time::Instant::now()));
                0.0
            };
            adj.set_value(x.clamp(0.0, over));
            glib::ControlFlow::Continue
        });
        *self.run.tick.borrow_mut() = Some(id);
    }

    fn stop(&self) {
        if let Some(id) = self.run.tick.borrow_mut().take() {
            id.remove();
        }
        self.run.started.set(None);
        self.widget.hadjustment().set_value(0.0);
    }
}

// ---------------------------------------------------------------------------------------------------------
// Frosted: frosted glass. Paints the part of a backdrop widget that lies behind it, blurred, under a
// translucent tint (its CSS `color`), then its child. Its CSS background must be the opaque colour behind the
// backdrop (the page), or the sharp content would show through the blur. Used for the bars over scrolling text.

mod frosted_imp {
    use super::*;

    pub struct Frosted {
        pub child: RefCell<Option<gtk::Widget>>,
        pub backdrop: glib::WeakRef<gtk::Widget>,
        pub paintable: RefCell<Option<gtk::WidgetPaintable>>,
        pub radius: Cell<f32>,
        pub blur: Cell<f32>,
    }

    impl Default for Frosted {
        fn default() -> Self {
            Frosted {
                child: RefCell::new(None),
                backdrop: glib::WeakRef::new(),
                paintable: RefCell::new(None),
                radius: Cell::new(999.0),
                blur: Cell::new(18.0),
            }
        }
    }

    #[glib::object_subclass]
    impl ObjectSubclass for Frosted {
        const NAME: &'static str = "GBookFrosted";
        type Type = super::Frosted;
        type ParentType = gtk::Widget;
    }

    impl ObjectImpl for Frosted {
        fn dispose(&self) {
            if let Some(c) = self.child.take() {
                c.unparent();
            }
        }
    }

    impl WidgetImpl for Frosted {
        fn request_mode(&self) -> gtk::SizeRequestMode {
            self.child
                .borrow()
                .as_ref()
                .map(|c| c.request_mode())
                .unwrap_or(gtk::SizeRequestMode::ConstantSize)
        }

        fn measure(&self, orientation: gtk::Orientation, for_size: i32) -> (i32, i32, i32, i32) {
            match self.child.borrow().as_ref() {
                Some(c) => c.measure(orientation, for_size),
                None => (0, 0, -1, -1),
            }
        }

        fn size_allocate(&self, width: i32, height: i32, baseline: i32) {
            if let Some(c) = self.child.borrow().as_ref() {
                c.allocate(width, height, baseline, None);
            }
        }

        fn snapshot(&self, s: &gtk::Snapshot) {
            let obj = self.obj();
            let (w, h) = (obj.width() as f32, obj.height() as f32);
            let rect = graphene::Rect::new(0.0, 0.0, w, h);
            let r = self.radius.get().min(h / 2.0).min(w / 2.0);
            let corner = graphene::Size::new(r, r);
            s.push_rounded_clip(&gsk::RoundedRect::new(rect, corner, corner, corner, corner));
            if let (Some(p), Some(back)) =
                (self.paintable.borrow().as_ref(), self.backdrop.upgrade())
                && let Some(b) = obj.compute_bounds(&back)
            {
                let blur = self.blur.get();
                s.push_blur(blur as f64);
                // Only the area just around the glass goes through the blur.
                s.push_clip(&graphene::Rect::new(
                    -blur * 2.0,
                    -blur * 2.0,
                    w + blur * 4.0,
                    h + blur * 4.0,
                ));
                s.save();
                s.translate(&graphene::Point::new(-b.x(), -b.y()));
                p.snapshot(s, back.width() as f64, back.height() as f64);
                s.restore();
                s.pop();
                s.pop();
            }
            s.append_color(&obj.color(), &rect);
            s.pop();
            if let Some(c) = self.child.borrow().as_ref() {
                obj.snapshot_child(c, s);
            }
        }
    }
}

glib::wrapper! {
    pub struct Frosted(ObjectSubclass<frosted_imp::Frosted>) @extends gtk::Widget, @implements gtk::Accessible, gtk::Buildable, gtk::ConstraintTarget;
}

impl Frosted {
    /// Frosted glass over `backdrop` (a widget painted behind this one) holding `child`.
    pub fn new(backdrop: &impl IsA<gtk::Widget>, child: &impl IsA<gtk::Widget>) -> Frosted {
        let f: Frosted = glib::Object::new();
        f.add_css_class("frosted");
        child.set_parent(&f);
        *f.imp().child.borrow_mut() = Some(child.clone().upcast());
        f.imp().backdrop.set(Some(backdrop.upcast_ref()));
        let p = gtk::WidgetPaintable::new(Some(backdrop));
        // Redraw the glass whenever what's behind it changes, e.g. while the page scrolls.
        let weak = f.downgrade();
        p.connect_invalidate_contents(move |_| {
            if let Some(f) = weak.upgrade() {
                f.queue_draw();
            }
        });
        *f.imp().paintable.borrow_mut() = Some(p);
        f
    }
}
