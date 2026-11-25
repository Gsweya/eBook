//! A book's pictures over the whole window, opened by clicking one in the reader. The wheel, pinch, Ctrl + and
//! − or a double-click zoom; dragging moves a zoomed picture; the arrows (or ← →) go through the chapter's
//! other pictures; Escape, the close button or a click beside the picture closes it.

use std::cell::{Cell, RefCell};
use std::path::PathBuf;
use std::rc::{Rc, Weak};
use std::time::{Duration, Instant};

use gtk::prelude::*;
use gtk::{gdk, gio, glib, graphene, gsk};

use crate::state::AppRc;
use crate::widgets::*;

const MAX_ZOOM: f64 = 8.0;
const DOUBLE_CLICK_ZOOM: f64 = 2.5;
/// Small pictures are shown at up to this many times their own size when fitted to the window.
const MAX_FIT_UPSCALE: f64 = 2.0;
/// Pictures are decoded no wider than this, which is plenty to zoom into.
const DECODE_WIDTH: i32 = 4096;

mod paint_imp {
    use super::*;
    use gtk::subclass::prelude::*;

    /// Draws the picture fitted to the area it's given, then zoomed by `scale` about the centre and moved by
    /// `offset`, clipped to that area.
    #[derive(Default)]
    pub struct ViewPaintable {
        pub texture: RefCell<Option<gdk::Texture>>,
        /// The picture's own size in window units (texture pixels over the screen scale).
        pub natural: Cell<(f64, f64)>,
        pub scale: Cell<f64>,
        pub offset: Cell<(f64, f64)>,
    }

    #[glib::object_subclass]
    impl ObjectSubclass for ViewPaintable {
        const NAME: &'static str = "GBookViewPaintable";
        type Type = super::ViewPaintable;
        type Interfaces = (gdk::Paintable,);
    }

    impl ObjectImpl for ViewPaintable {}

    impl gdk::subclass::paintable::PaintableImpl for ViewPaintable {
        fn snapshot(&self, snapshot: &gdk::Snapshot, width: f64, height: f64) {
            let Some(t) = self.texture.borrow().clone() else {
                return;
            };
            let Some(s) = snapshot.downcast_ref::<gtk::Snapshot>() else {
                return;
            };
            let (x, y, w, h) = self.obj().image_rect(width, height);
            s.push_clip(&graphene::Rect::new(0.0, 0.0, width as f32, height as f32));
            s.append_scaled_texture(
                &t,
                gsk::ScalingFilter::Trilinear,
                &graphene::Rect::new(x as f32, y as f32, w as f32, h as f32),
            );
            s.pop();
        }
    }
}

glib::wrapper! {
    pub struct ViewPaintable(ObjectSubclass<paint_imp::ViewPaintable>) @implements gdk::Paintable;
}

impl ViewPaintable {
    fn new() -> Self {
        let p: ViewPaintable = glib::Object::new();
        p.imp().scale.set(1.0);
        p
    }

    fn imp(&self) -> &paint_imp::ViewPaintable {
        use gtk::subclass::prelude::ObjectSubclassIsExt;
        ObjectSubclassIsExt::imp(self)
    }

    fn set_texture(&self, t: &gdk::Texture, screen_scale: i32) {
        let k = screen_scale.max(1) as f64;
        *self.imp().texture.borrow_mut() = Some(t.clone());
        self.imp()
            .natural
            .set((t.width() as f64 / k, t.height() as f64 / k));
    }

    fn has_texture(&self) -> bool {
        self.imp().texture.borrow().is_some()
    }

    /// Size of the picture fitted into a `w` × `h` area, before zooming.
    fn fitted(&self, w: f64, h: f64) -> (f64, f64) {
        let (nw, nh) = self.imp().natural.get();
        if nw <= 0.0 || nh <= 0.0 {
            return (0.0, 0.0);
        }
        let k = (w / nw).min(h / nh).min(MAX_FIT_UPSCALE);
        (nw * k, nh * k)
    }

    /// Where the picture is drawn in a `w` × `h` area: x, y, width, height.
    fn image_rect(&self, w: f64, h: f64) -> (f64, f64, f64, f64) {
        let (fw, fh) = self.fitted(w, h);
        let s = self.imp().scale.get();
        let (ox, oy) = self.imp().offset.get();
        let (dw, dh) = (fw * s, fh * s);
        ((w - dw) / 2.0 + ox, (h - dh) / 2.0 + oy, dw, dh)
    }

    /// Keeps a zoomed picture covering the area: it can't be dragged past its own edges.
    fn clamp_offset(&self, o: (f64, f64), s: f64, w: f64, h: f64) -> (f64, f64) {
        let (fw, fh) = self.fitted(w, h);
        let mx = ((fw * s - w) / 2.0).max(0.0);
        let my = ((fh * s - h) / 2.0).max(0.0);
        (o.0.clamp(-mx, mx), o.1.clamp(-my, my))
    }

    fn scale(&self) -> f64 {
        self.imp().scale.get()
    }

    fn offset(&self) -> (f64, f64) {
        self.imp().offset.get()
    }

    fn set_view(&self, s: f64, o: (f64, f64)) {
        self.imp().scale.set(s);
        self.imp().offset.set(o);
        self.invalidate_contents();
    }

    /// Zoom and offset that put scale `ns` on the picture with the point (`px`, `py`) staying where it is.
    fn zoom_about(&self, ns: f64, px: f64, py: f64, w: f64, h: f64) -> (f64, (f64, f64)) {
        let ns = ns.clamp(1.0, MAX_ZOOM);
        let s = self.scale();
        let (ox, oy) = self.offset();
        let (rx, ry) = (px - w / 2.0, py - h / 2.0);
        let k = ns / s;
        let o = ((ox - rx) * k + rx, (oy - ry) * k + ry);
        (ns, self.clamp_offset(o, ns, w, h))
    }

    /// The picture's actual zoom: shown pixels per pixel of the decoded picture.
    fn actual_zoom(&self, w: f64, h: f64) -> f64 {
        let (nw, _) = self.imp().natural.get();
        let (fw, _) = self.fitted(w, h);
        if nw <= 0.0 {
            1.0
        } else {
            fw / nw * self.scale()
        }
    }
}

struct Viewer {
    app: AppRc,
    paths: Vec<PathBuf>,
    /// Name for saved copies, e.g. the book's title.
    save_name: String,
    index: Cell<usize>,
    paint: ViewPaintable,
    picture: gtk::Picture,
    counter: gtk::Label,
    zoom_label: gtk::Label,
    prev: gtk::Button,
    next: gtk::Button,
    note: gtk::Revealer,
    note_label: gtk::Label,
    pointer: Cell<(f64, f64)>,
    /// Bumped by every picture load and zoom animation, so a stale one stops.
    load_gen: Cell<u64>,
    anim_gen: Cell<u64>,
    close: RefCell<Option<Rc<dyn Fn()>>>,
}

fn with(w: &Weak<Viewer>, f: impl FnOnce(&Rc<Viewer>)) {
    if let Some(v) = w.upgrade() {
        f(&v);
    }
}

/// Opens the viewer on `paths[start]`. `preview`, the reader's smaller copy, shows until the sharp one is ready.
pub fn open(
    app: &AppRc,
    paths: Vec<PathBuf>,
    start: usize,
    preview: Option<gdk::Texture>,
    save_name: &str,
) {
    let Some(host) = app.sheet_host.borrow().clone() else {
        return;
    };
    if paths.is_empty() {
        return;
    }
    let layer = gtk::Overlay::new();
    layer.add_css_class("image-viewer");
    layer.set_focusable(true);

    let paint = ViewPaintable::new();
    let picture = gtk::Picture::for_paintable(&paint);
    picture.set_content_fit(gtk::ContentFit::Fill);
    picture.set_can_shrink(true);
    picture.set_hexpand(true);
    picture.set_vexpand(true);
    picture.set_tooltip_text(Some("Double-click or scroll to zoom"));
    // Clear of the bar above and the arrows at the sides.
    picture.set_margin_top(76);
    picture.set_margin_bottom(24);
    let side = if paths.len() > 1 { 76 } else { 24 };
    picture.set_margin_start(side);
    picture.set_margin_end(side);
    layer.set_child(Some(&picture));

    let close_btn = circle_button("close", "Close (Esc)");
    let counter = label("", &["viewer-counter"]);
    let zoom_out = circle_button("zoom-out", "Zoom out (−)");
    let zoom_in = circle_button("zoom-in", "Zoom in (+)");
    let zoom_label = label("", &["viewer-zoom"]);
    zoom_label.set_xalign(0.5);
    zoom_label.set_width_chars(5);
    let fit = gtk::Button::new();
    fit.add_css_class("pill");
    fit.add_css_class("viewer-fit");
    fit.set_child(Some(&zoom_label));
    fit.set_tooltip_text(Some("Fit to window (0)"));
    fit.set_valign(gtk::Align::Center);
    let save = circle_button("download", "Save a copy… (Ctrl+S)");
    let bar = hbox(10);
    bar.add_css_class("viewer-bar");
    bar.set_valign(gtk::Align::Start);
    bar.set_margin_top(16);
    bar.set_margin_start(16);
    bar.set_margin_end(16);
    bar.append(&close_btn);
    let left = spacer();
    bar.append(&left);
    bar.append(&counter);
    let right = spacer();
    bar.append(&right);
    bar.append(&zoom_out);
    bar.append(&fit);
    bar.append(&zoom_in);
    bar.append(&save);
    layer.add_overlay(&bar);

    let prev = circle_button("chevron-left", "Previous picture (←)");
    prev.set_halign(gtk::Align::Start);
    prev.set_margin_start(16);
    let next = circle_button("chevron-right", "Next picture (→)");
    next.set_halign(gtk::Align::End);
    next.set_margin_end(16);
    for b in [&prev, &next] {
        b.add_css_class("viewer-nav");
        b.set_visible(paths.len() > 1);
        layer.add_overlay(b);
    }

    let note_label = label("", &["toast"]);
    let note = gtk::Revealer::new();
    note.set_transition_type(gtk::RevealerTransitionType::Crossfade);
    note.set_halign(gtk::Align::Center);
    note.set_valign(gtk::Align::End);
    note.set_margin_bottom(28);
    note.set_can_target(false);
    note.set_child(Some(&note_label));
    layer.add_overlay(&note);

    let v = Rc::new(Viewer {
        app: app.clone(),
        paths,
        save_name: save_name.to_string(),
        index: Cell::new(start),
        paint,
        picture: picture.clone(),
        counter,
        zoom_label,
        prev: prev.clone(),
        next: next.clone(),
        note,
        note_label,
        pointer: Cell::new((0.0, 0.0)),
        load_gen: Cell::new(0),
        anim_gen: Cell::new(0),
        close: RefCell::new(None),
    });

    let rev = gtk::Revealer::new();
    rev.set_transition_type(gtk::RevealerTransitionType::Crossfade);
    rev.set_transition_duration(160);
    rev.set_child(Some(&layer));
    host.add_overlay(&rev);
    rev.set_reveal_child(true);

    let closed = Cell::new(false);
    let close: Rc<dyn Fn()> = {
        let (rev, host) = (rev.clone(), host.clone());
        Rc::new(move || {
            if closed.replace(true) {
                return;
            }
            rev.set_reveal_child(false);
            let (r, h) = (rev.clone(), host.clone());
            glib::timeout_add_local_once(Duration::from_millis(200), move || h.remove_overlay(&r));
            if let Some(w) = rev.root().and_downcast::<gtk::Window>() {
                gtk::prelude::GtkWindowExt::set_focus(&w, None::<&gtk::Widget>);
            }
        })
    };
    // The viewer lives as long as its widgets do.
    let keep = RefCell::new(Some(v.clone()));
    layer.connect_unrealize(move |_| {
        keep.borrow_mut().take();
    });
    *v.close.borrow_mut() = Some(close.clone());

    let c = close.clone();
    close_btn.connect_clicked(move |_| c());
    let w = Rc::downgrade(&v);
    prev.connect_clicked(move |_| with(&w, |v| v.step(-1)));
    let w = Rc::downgrade(&v);
    next.connect_clicked(move |_| with(&w, |v| v.step(1)));
    let w = Rc::downgrade(&v);
    zoom_in.connect_clicked(move |_| with(&w, |v| v.zoom_center(1.5)));
    let w = Rc::downgrade(&v);
    zoom_out.connect_clicked(move |_| with(&w, |v| v.zoom_center(1.0 / 1.5)));
    let w = Rc::downgrade(&v);
    fit.connect_clicked(move |_| with(&w, |v| v.animate_to(1.0, (0.0, 0.0))));
    let w = Rc::downgrade(&v);
    save.connect_clicked(move |_| with(&w, |v| v.save_copy()));

    v.install_input(&layer);
    // A click on the dark margins around the picture closes the viewer too.
    let margin_click = gtk::GestureClick::new();
    let (c, l) = (close.clone(), layer.downgrade());
    margin_click.connect_released(move |_, n, x, y| {
        if let Some(l) = l.upgrade()
            && n == 1
            && l.pick(x, y, gtk::PickFlags::DEFAULT)
                .is_some_and(|w| w == *l.upcast_ref::<gtk::Widget>())
        {
            c();
        }
    });
    layer.add_controller(margin_click);
    if let Some(t) = preview {
        v.paint.set_texture(&t, 1);
        // Shown at the size the sharp copy will have, so nothing jumps when it arrives.
        if let Some((pw, ph)) = gdk_pixbuf_size(&v.paths[start]) {
            let k = app.ui_scale().max(1) as f64;
            let wanted = (
                pw.min(DECODE_WIDTH) as f64 / k,
                ph as f64 * pw.min(DECODE_WIDTH) as f64 / pw.max(1) as f64 / k,
            );
            v.paint.imp().natural.set(wanted);
        }
    }
    v.load(start);
    let l = layer.clone();
    glib::idle_add_local_once(move || {
        l.grab_focus();
    });
}

/// Pixel size of the image file at `path`, without decoding it.
fn gdk_pixbuf_size(path: &std::path::Path) -> Option<(i32, i32)> {
    gtk::gdk_pixbuf::Pixbuf::file_info(path).map(|(_, w, h)| (w, h))
}

impl Viewer {
    fn area(&self) -> (f64, f64) {
        (self.picture.width() as f64, self.picture.height() as f64)
    }

    fn close(&self) {
        if let Some(c) = self.close.borrow().clone() {
            c();
        }
    }

    fn load(self: &Rc<Self>, i: usize) {
        self.index.set(i);
        let n = self.paths.len();
        self.counter.set_text(&if n > 1 {
            format!("{} of {}", i + 1, n)
        } else {
            String::new()
        });
        self.prev.set_sensitive(i > 0);
        self.next.set_sensitive(i + 1 < n);
        self.anim_gen.set(self.anim_gen.get() + 1);
        self.paint.set_view(1.0, (0.0, 0.0));
        self.refresh();
        let seq = self.load_gen.get() + 1;
        self.load_gen.set(seq);
        let path = self.paths[i].clone();
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let img = gio::spawn_blocking(move || crate::media::decode_scaled(&path, DECODE_WIDTH))
                .await
                .ok()
                .flatten();
            with(&w, |v| {
                if v.load_gen.get() != seq {
                    return;
                }
                match img {
                    Some(d) => {
                        v.paint.set_texture(&d.texture(), v.app.ui_scale());
                        v.paint.invalidate_contents();
                        v.refresh();
                    }
                    None if !v.paint.has_texture() => v.show_note("Couldn't open this picture"),
                    None => {}
                }
            });
        });
    }

    fn step(self: &Rc<Self>, d: i32) {
        let i = self.index.get() as i32 + d;
        if i >= 0 && (i as usize) < self.paths.len() {
            *self.paint.imp().texture.borrow_mut() = None;
            self.load(i as usize);
        }
    }

    /// Updates the zoom readout and the pointer for the current zoom.
    fn refresh(&self) {
        let (w, h) = self.area();
        let zoomed = self.paint.scale() > 1.001;
        self.zoom_label.set_text(&if !zoomed {
            "Fit".to_string()
        } else {
            format!("{:.0}%", self.paint.actual_zoom(w, h) * 100.0)
        });
        self.update_cursor(false);
    }

    fn update_cursor(&self, dragging: bool) {
        let (w, h) = self.area();
        let (px, py) = self.pointer.get();
        let (x, y, iw, ih) = self.paint.image_rect(w, h);
        let over = px >= x && px <= x + iw && py >= y && py <= y + ih;
        let name = if !over {
            "default"
        } else if dragging {
            "grabbing"
        } else if self.paint.scale() > 1.001 {
            "grab"
        } else {
            "default"
        };
        self.picture.set_cursor_from_name(Some(name));
    }

    fn set_view(&self, s: f64, o: (f64, f64)) {
        self.paint.set_view(s, o);
        self.refresh();
    }

    /// Multiplies the zoom by `k` about the point (`px`, `py`), at once.
    fn zoom_at(&self, k: f64, px: f64, py: f64) {
        self.anim_gen.set(self.anim_gen.get() + 1);
        let (w, h) = self.area();
        let (s, o) = self.paint.zoom_about(self.paint.scale() * k, px, py, w, h);
        self.set_view(s, o);
    }

    fn zoom_center(self: &Rc<Self>, k: f64) {
        let (w, h) = self.area();
        let (s, o) = self
            .paint
            .zoom_about(self.paint.scale() * k, w / 2.0, h / 2.0, w, h);
        self.animate_to(s, o);
    }

    /// Eases to zoom `s` and offset `o` over a fifth of a second.
    fn animate_to(self: &Rc<Self>, s: f64, o: (f64, f64)) {
        let seq = self.anim_gen.get() + 1;
        self.anim_gen.set(seq);
        let (s0, o0) = (self.paint.scale(), self.paint.offset());
        let start = Instant::now();
        let w = Rc::downgrade(self);
        self.picture.add_tick_callback(move |_, _| {
            let Some(v) = w.upgrade() else {
                return glib::ControlFlow::Break;
            };
            if v.anim_gen.get() != seq {
                return glib::ControlFlow::Break;
            }
            let t = (start.elapsed().as_secs_f64() / 0.2).min(1.0);
            let e = 1.0 - (1.0 - t).powi(3);
            v.set_view(
                s0 + (s - s0) * e,
                (o0.0 + (o.0 - o0.0) * e, o0.1 + (o.1 - o0.1) * e),
            );
            if t >= 1.0 {
                glib::ControlFlow::Break
            } else {
                glib::ControlFlow::Continue
            }
        });
    }

    fn toggle_zoom_at(self: &Rc<Self>, px: f64, py: f64) {
        if self.paint.scale() > 1.001 {
            self.animate_to(1.0, (0.0, 0.0));
        } else {
            let (w, h) = self.area();
            let (s, o) = self.paint.zoom_about(DOUBLE_CLICK_ZOOM, px, py, w, h);
            self.animate_to(s, o);
        }
    }

    fn pan_by(&self, dx: f64, dy: f64) {
        let (w, h) = self.area();
        let (ox, oy) = self.paint.offset();
        let s = self.paint.scale();
        let o = self.paint.clamp_offset((ox + dx, oy + dy), s, w, h);
        self.set_view(s, o);
    }

    fn show_note(self: &Rc<Self>, text: &str) {
        self.note_label.set_text(text);
        self.note.set_reveal_child(true);
        let w = Rc::downgrade(self);
        let t = text.to_string();
        glib::timeout_add_local_once(Duration::from_millis(2200), move || {
            with(&w, |v| {
                if v.note_label.text() == t {
                    v.note.set_reveal_child(false);
                }
            })
        });
    }

    /// Saves the picture file, as it came in the book, where the user picks.
    fn save_copy(self: &Rc<Self>) {
        let src = self.paths[self.index.get()].clone();
        let ext = src.extension().and_then(|e| e.to_str()).unwrap_or("png");
        let name = if self.paths.len() > 1 {
            format!(
                "{} - picture {}.{ext}",
                self.save_name,
                self.index.get() + 1
            )
        } else {
            format!("{} - picture.{ext}", self.save_name)
        };
        let dialog = gtk::FileDialog::builder()
            .title("Save Picture")
            .initial_name(name.replace(['/', '\\'], "-"))
            .modal(true)
            .build();
        let w = Rc::downgrade(self);
        dialog.save(
            self.app.window().as_ref(),
            gio::Cancellable::NONE,
            move |res| {
                let Some(path) = res.ok().and_then(|f| f.path()) else {
                    return;
                };
                let r = std::fs::copy(&src, &path);
                with(&w, |v| match r {
                    Ok(_) => v.show_note("Picture saved"),
                    Err(e) => v.show_note(&format!("Couldn't save the picture: {e}")),
                });
            },
        );
    }

    fn install_input(self: &Rc<Self>, layer: &gtk::Overlay) {
        let keys = gtk::EventControllerKey::new();
        keys.set_propagation_phase(gtk::PropagationPhase::Capture);
        let w = Rc::downgrade(self);
        keys.connect_key_pressed(move |_, key, _, mods| {
            let Some(v) = w.upgrade() else {
                return glib::Propagation::Proceed;
            };
            let ctrl = mods.contains(gdk::ModifierType::CONTROL_MASK);
            match key {
                gdk::Key::Escape => v.close(),
                gdk::Key::Left | gdk::Key::Page_Up => v.step(-1),
                gdk::Key::Right | gdk::Key::Page_Down | gdk::Key::space => v.step(1),
                gdk::Key::plus | gdk::Key::equal | gdk::Key::KP_Add => v.zoom_center(1.5),
                gdk::Key::minus | gdk::Key::KP_Subtract => v.zoom_center(1.0 / 1.5),
                gdk::Key::_0 | gdk::Key::KP_0 => v.animate_to(1.0, (0.0, 0.0)),
                gdk::Key::s if ctrl => v.save_copy(),
                _ => return glib::Propagation::Proceed,
            }
            glib::Propagation::Stop
        });
        layer.add_controller(keys);

        let motion = gtk::EventControllerMotion::new();
        let w = Rc::downgrade(self);
        motion.connect_motion(move |_, x, y| {
            with(&w, |v| {
                v.pointer.set((x, y));
                v.update_cursor(false);
            })
        });
        self.picture.add_controller(motion);

        // A mouse wheel zooms about the pointer; a touchpad scrolls a zoomed picture, and zooms with Ctrl.
        let scroll = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::BOTH_AXES);
        let w = Rc::downgrade(self);
        scroll.connect_scroll(move |c, dx, dy| {
            let Some(v) = w.upgrade() else {
                return glib::Propagation::Proceed;
            };
            let (px, py) = v.pointer.get();
            let ctrl = c
                .current_event_state()
                .contains(gdk::ModifierType::CONTROL_MASK);
            if c.unit() == gdk::ScrollUnit::Wheel {
                v.zoom_at(1.25f64.powf(-dy), px, py);
            } else if ctrl {
                v.zoom_at((-dy * 0.01).exp(), px, py);
            } else if v.paint.scale() > 1.001 {
                v.pan_by(-dx, -dy);
            }
            glib::Propagation::Stop
        });
        self.picture.add_controller(scroll);

        let pinch = gtk::GestureZoom::new();
        let base = Rc::new(Cell::new(1.0));
        let (w, b) = (Rc::downgrade(self), base.clone());
        pinch.connect_begin(move |_, _| with(&w, |v| b.set(v.paint.scale())));
        let w = Rc::downgrade(self);
        pinch.connect_scale_changed(move |g, s| {
            with(&w, |v| {
                let (px, py) = g.bounding_box_center().unwrap_or(v.pointer.get());
                let target = base.get() * s;
                v.zoom_at(target / v.paint.scale(), px, py);
            })
        });
        self.picture.add_controller(pinch);

        let drag = gtk::GestureDrag::new();
        let from = Rc::new(Cell::new((0.0, 0.0)));
        let (w, f) = (Rc::downgrade(self), from.clone());
        drag.connect_drag_begin(move |_, _, _| {
            with(&w, |v| {
                v.anim_gen.set(v.anim_gen.get() + 1);
                f.set(v.paint.offset());
            })
        });
        let (w, f) = (Rc::downgrade(self), from.clone());
        drag.connect_drag_update(move |_, dx, dy| {
            with(&w, |v| {
                if v.paint.scale() <= 1.001 {
                    return;
                }
                let (w, h) = v.area();
                let s = v.paint.scale();
                let (ox, oy) = f.get();
                v.paint
                    .set_view(s, v.paint.clamp_offset((ox + dx, oy + dy), s, w, h));
                v.update_cursor(true);
            })
        });
        let w = Rc::downgrade(self);
        drag.connect_drag_end(move |_, _, _| with(&w, |v| v.update_cursor(false)));
        self.picture.add_controller(drag);

        // A double-click zooms in on the spot or back out; a click beside the picture closes the viewer.
        let click = gtk::GestureClick::new();
        let w = Rc::downgrade(self);
        click.connect_released(move |_, n, x, y| {
            with(&w, |v| {
                let (aw, ah) = v.area();
                let (ix, iy, iw, ih) = v.paint.image_rect(aw, ah);
                let over = x >= ix && x <= ix + iw && y >= iy && y <= iy + ih;
                if n == 2 && over {
                    v.toggle_zoom_at(x, y);
                } else if n == 1 && !over {
                    v.close();
                }
            })
        });
        self.picture.add_controller(click);
    }
}
