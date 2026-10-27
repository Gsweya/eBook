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
    let track = gtk::Overlay::new();
    track.add_css_class("track");
    if thick {
        track.add_css_class("thick");
    }
    let base = hbox(0);
    base.set_hexpand(true);
    track.set_child(Some(&base));
    let bar = gtk::DrawingArea::new();
    bar.add_css_class("bar");
    bar.set_halign(gtk::Align::Start);
    let frac = fraction.clamp(0.0, 1.0);
    // The bar's width follows the track's allocation.
    track.add_overlay(&bar);
    track.set_measure_overlay(&bar, false);
    let b2 = bar.clone();
    track.connect_get_child_position(move |t, w| {
        if w == b2.upcast_ref::<gtk::Widget>() {
            let width = ((t.width() as f32) * frac).round() as i32;
            Some(gtk::gdk::Rectangle::new(
                0,
                0,
                width.max(if frac > 0.0 { t.height() } else { 0 }),
                t.height(),
            ))
        } else {
            None
        }
    });
    track.upcast()
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
