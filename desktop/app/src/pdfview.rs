//! PDF page view: pages rendered by Poppler on worker threads as they scroll into view, with zoom. Only the
//! pages near the viewport keep their textures, so memory stays flat however long the document is.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::rc::{Rc, Weak};
use std::time::{Duration, Instant};

use gbook_core::{Book, ReadingPosition};
use gtk::prelude::*;
use gtk::{gdk, gio, glib};

use crate::state::{AppRc, today};
use crate::widgets::*;

const GAP: i32 = 18;
const TOP: i32 = 76;
const IDLE: Duration = Duration::from_secs(180);

struct PdfView {
    app: AppRc,
    id: String,
    path: PathBuf,
    sizes: Vec<(f64, f64)>,
    scroller: gtk::ScrolledWindow,
    column: gtk::Box,
    pages: Vec<gtk::Picture>,
    zoom: Cell<f64>,
    width: Cell<i32>,
    /// Width each page was last rendered at, and pages being rendered.
    rendered: RefCell<HashMap<usize, i32>>,
    inflight: RefCell<HashSet<(usize, i32)>>,
    status: gtk::Label,
    last_input: Cell<Instant>,
    last_tick: Cell<Instant>,
    unsaved: Cell<i64>,
    seen: Cell<(usize, Instant)>,
    counted: RefCell<HashSet<usize>>,
    restore: Cell<Option<usize>>,
    on_back: Rc<dyn Fn()>,
    timer: RefCell<Option<glib::SourceId>>,
}

pub fn open(app: &AppRc, book: &Book, on_back: Rc<dyn Fn()>) -> gtk::Widget {
    let path = app.paths.book_file(book);
    let sizes = crate::media::pdf_page_sizes(&path);
    let root = gtk::Overlay::new();
    root.add_css_class("reader");
    root.add_css_class("pdf");
    root.set_focusable(true);
    let column = vbox(GAP);
    column.set_margin_top(TOP);
    column.set_margin_bottom(60);
    column.set_halign(gtk::Align::Center);
    let scroller = gtk::ScrolledWindow::new();
    scroller.set_child(Some(&column));
    root.set_child(Some(&scroller));
    let pages: Vec<gtk::Picture> = sizes
        .iter()
        .map(|_| {
            let p = gtk::Picture::new();
            p.add_css_class("pdf-page");
            p.set_can_shrink(true);
            p.set_content_fit(gtk::ContentFit::Fill);
            column.append(&p);
            p
        })
        .collect();

    let back = circle_button("arrow-back-ios-new", "Back to Library (Esc)");
    let status = label("", &["reader-chrome", "text2"]);
    let zoom_out = circle_button("zoom-out", "Zoom out (Ctrl −)");
    let zoom_in = circle_button("zoom-in", "Zoom in (Ctrl +)");
    let end = hbox(10);
    end.append(&zoom_out);
    end.append(&zoom_in);
    let top = top_bar(&back, Some(status.upcast_ref()), &end, false, None);
    root.add_overlay(&top);
    if sizes.is_empty() {
        let l = label("This PDF couldn't be opened", &["section-title"]);
        l.set_halign(gtk::Align::Center);
        l.set_valign(gtk::Align::Center);
        root.add_overlay(&l);
    }

    let start_page = book.position.pdf_page.max(0) as usize;
    let v = Rc::new(PdfView {
        app: app.clone(),
        id: book.id.clone(),
        path,
        sizes,
        scroller: scroller.clone(),
        column,
        pages,
        zoom: Cell::new(1.0),
        width: Cell::new(0),
        rendered: RefCell::new(HashMap::new()),
        inflight: RefCell::new(HashSet::new()),
        status,
        last_input: Cell::new(Instant::now()),
        last_tick: Cell::new(Instant::now()),
        unsaved: Cell::new(0),
        seen: Cell::new((usize::MAX, Instant::now())),
        counted: RefCell::new(HashSet::new()),
        restore: Cell::new((start_page > 0).then_some(start_page)),
        on_back,
        timer: RefCell::new(None),
    });

    let w = Rc::downgrade(&v);
    back.connect_clicked(move |_| with(&w, |v| (v.on_back)()));
    let w = Rc::downgrade(&v);
    zoom_in.connect_clicked(move |_| with(&w, |v| v.set_zoom(v.zoom.get() * 1.2)));
    let w = Rc::downgrade(&v);
    zoom_out.connect_clicked(move |_| with(&w, |v| v.set_zoom(v.zoom.get() / 1.2)));
    let sizer = gtk::DrawingArea::new();
    sizer.set_can_target(false);
    sizer.set_valign(gtk::Align::Start);
    sizer.set_content_height(1);
    root.add_overlay(&sizer);
    let w = Rc::downgrade(&v);
    sizer.connect_resize(move |_, width, _| {
        with(&w, |v| {
            v.width.set(width);
            v.layout();
        })
    });
    let w = Rc::downgrade(&v);
    scroller.vadjustment().connect_value_changed(move |_| {
        with(&w, |v| {
            v.last_input.set(Instant::now());
            v.refresh();
        })
    });

    let keys = gtk::EventControllerKey::new();
    keys.set_propagation_phase(gtk::PropagationPhase::Capture);
    let w = Rc::downgrade(&v);
    keys.connect_key_pressed(move |_, key, _, mods| {
        let Some(v) = w.upgrade() else {
            return glib::Propagation::Proceed;
        };
        v.last_input.set(Instant::now());
        let ctrl = mods.contains(gdk::ModifierType::CONTROL_MASK);
        let adj = v.scroller.vadjustment();
        let page = adj.page_size() - 80.0;
        match key {
            gdk::Key::Escape => (v.on_back)(),
            gdk::Key::equal | gdk::Key::plus | gdk::Key::KP_Add if ctrl => {
                v.set_zoom(v.zoom.get() * 1.2)
            }
            gdk::Key::minus | gdk::Key::KP_Subtract if ctrl => v.set_zoom(v.zoom.get() / 1.2),
            gdk::Key::_0 if ctrl => v.set_zoom(1.0),
            gdk::Key::Page_Down | gdk::Key::space | gdk::Key::Right => {
                adj.set_value(adj.value() + page)
            }
            gdk::Key::Page_Up | gdk::Key::Left => adj.set_value(adj.value() - page),
            gdk::Key::Down => adj.set_value(adj.value() + 60.0),
            gdk::Key::Up => adj.set_value(adj.value() - 60.0),
            gdk::Key::Home => adj.set_value(0.0),
            gdk::Key::End => adj.set_value(adj.upper()),
            gdk::Key::F11 => {
                if let Some(win) = v.app.window() {
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
    let wheel = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::VERTICAL);
    wheel.set_propagation_phase(gtk::PropagationPhase::Capture);
    let w = Rc::downgrade(&v);
    wheel.connect_scroll(move |c, _, dy| {
        let Some(v) = w.upgrade() else {
            return glib::Propagation::Proceed;
        };
        v.last_input.set(Instant::now());
        if c.current_event_state()
            .contains(gdk::ModifierType::CONTROL_MASK)
        {
            v.set_zoom(v.zoom.get() * if dy < 0.0 { 1.1 } else { 1.0 / 1.1 });
            return glib::Propagation::Stop;
        }
        glib::Propagation::Proceed
    });
    scroller.add_controller(wheel);
    let pinch = gtk::GestureZoom::new();
    let base = Rc::new(Cell::new(1.0));
    let (w, b) = (Rc::downgrade(&v), base.clone());
    pinch.connect_begin(move |_, _| with(&w, |v| b.set(v.zoom.get())));
    let w = Rc::downgrade(&v);
    pinch.connect_scale_changed(move |_, s| with(&w, |v| v.set_zoom(base.get() * s)));
    scroller.add_controller(pinch);

    let w = Rc::downgrade(&v);
    let t = glib::timeout_add_local(Duration::from_secs(5), move || match w.upgrade() {
        Some(v) => {
            v.tick();
            glib::ControlFlow::Continue
        }
        None => glib::ControlFlow::Break,
    });
    *v.timer.borrow_mut() = Some(t);
    let keep = RefCell::new(Some(v.clone()));
    root.connect_unrealize(move |_| {
        if let Some(v) = keep.borrow_mut().take() {
            v.accrue();
            v.flush_time();
            v.save();
            if let Some(t) = v.timer.borrow_mut().take() {
                t.remove();
            }
        }
    });
    root.upcast()
}

fn with(w: &Weak<PdfView>, f: impl FnOnce(&Rc<PdfView>)) {
    if let Some(v) = w.upgrade() {
        f(&v);
    }
}

impl PdfView {
    fn page_width(&self) -> i32 {
        let base = (self.width.get() - 48).clamp(200, 900) as f64;
        (base * self.zoom.get()).round() as i32
    }

    fn page_height(&self, i: usize) -> i32 {
        let (w, h) = self.sizes[i];
        (self.page_width() as f64 * h / w.max(1.0)).round() as i32
    }

    fn set_zoom(self: &Rc<Self>, z: f64) {
        let z = z.clamp(0.4, 4.0);
        let adj = self.scroller.vadjustment();
        // Keep the same spot of the document in view.
        let frac = if adj.upper() > 0.0 {
            adj.value() / adj.upper()
        } else {
            0.0
        };
        self.zoom.set(z);
        self.layout();
        let w = Rc::downgrade(self);
        glib::idle_add_local_once(move || {
            with(&w, |v| {
                let adj = v.scroller.vadjustment();
                adj.set_value(frac * adj.upper());
            })
        });
    }

    fn layout(self: &Rc<Self>) {
        if self.width.get() <= 0 {
            return;
        }
        let pw = self.page_width();
        for (i, p) in self.pages.iter().enumerate() {
            p.set_size_request(pw, self.page_height(i));
        }
        self.column.queue_resize();
        if let Some(page) = self.restore.take() {
            let w = Rc::downgrade(self);
            glib::idle_add_local_once(move || {
                with(&w, |v| {
                    let y = v.page_top(page);
                    v.scroller.vadjustment().set_value(y as f64);
                    v.refresh();
                })
            });
        }
        self.refresh();
    }

    fn page_top(&self, page: usize) -> i32 {
        TOP + (0..page.min(self.pages.len()))
            .map(|i| self.page_height(i) + GAP)
            .sum::<i32>()
    }

    /// The page at the middle of the viewport.
    fn current_page(&self) -> usize {
        let adj = self.scroller.vadjustment();
        let mid = adj.value() + adj.page_size() / 3.0;
        let mut y = TOP as f64;
        for i in 0..self.pages.len() {
            y += (self.page_height(i) + GAP) as f64;
            if y > mid {
                return i;
            }
        }
        self.pages.len().saturating_sub(1)
    }

    /// Renders pages in and near the viewport and lets go of the rest.
    fn refresh(self: &Rc<Self>) {
        if self.pages.is_empty() || self.width.get() <= 0 {
            return;
        }
        let adj = self.scroller.vadjustment();
        // What's on screen plus half a screen either way.
        let (top, bottom) = (
            adj.value() - adj.page_size() * 0.5,
            adj.value() + adj.page_size() * 1.5,
        );
        let scale = self.scroller.scale_factor().max(1);
        let px = self.page_width() * scale;
        let mut y = TOP as f64;
        let mut keep = HashSet::new();
        for i in 0..self.pages.len() {
            let h = self.page_height(i) as f64;
            if y + h >= top && y <= bottom {
                keep.insert(i);
                if self.rendered.borrow().get(&i) != Some(&px)
                    && self.inflight.borrow_mut().insert((i, px))
                {
                    let (path, w) = (self.path.clone(), Rc::downgrade(self));
                    glib::spawn_future_local(async move {
                        let img = gio::spawn_blocking(move || {
                            crate::media::render_pdf_page(&path, i as i32, px)
                        })
                        .await
                        .ok()
                        .flatten();
                        with(&w, |v| {
                            v.inflight.borrow_mut().remove(&(i, px));
                            if let Some(img) = img
                                && v.page_width() * v.scroller.scale_factor().max(1) == px
                            {
                                v.pages[i].set_paintable(Some(&img.texture()));
                                v.rendered.borrow_mut().insert(i, px);
                            }
                        });
                    });
                }
            }
            y += h + GAP as f64;
        }
        let far: Vec<usize> = self
            .rendered
            .borrow()
            .keys()
            .filter(|i| !keep.contains(i))
            .copied()
            .collect();
        let freed = !far.is_empty();
        for i in far {
            self.pages[i].set_paintable(gdk::Paintable::NONE);
            self.rendered.borrow_mut().remove(&i);
        }
        if freed {
            crate::release_memory();
        }
        let cur = self.current_page();
        self.status
            .set_text(&format!("Page {} of {}", cur + 1, self.pages.len()));
        if self.seen.get().0 != cur {
            self.seen.set((cur, Instant::now()));
        }
    }

    fn save(&self) {
        if self.pages.is_empty() {
            return;
        }
        let page = self.current_page();
        let progress = (page + 1) as f32 / self.pages.len() as f32;
        let id = self.id.clone();
        self.app.update_quiet(|d| {
            let pos = d.book(&id).map(|b| b.position.clone()).unwrap_or_default();
            d.save_position(
                &id,
                ReadingPosition {
                    pdf_page: page as i32,
                    ..pos
                },
                progress,
            );
        });
    }

    fn accrue(&self) {
        let now = Instant::now();
        let since = now.duration_since(self.last_tick.replace(now));
        if self.app.window().is_some_and(|w| w.is_active())
            && now.duration_since(self.last_input.get()) < IDLE
        {
            self.unsaved
                .set(self.unsaved.get() + since.as_millis() as i64);
        }
    }

    fn flush_time(&self) {
        let ms = self.unsaved.replace(0);
        if ms > 0 {
            let (_, day) = today();
            let id = self.id.clone();
            if let Some(min) = self.app.update_quiet(|d| d.add_reading_time(&id, ms, &day)) {
                self.app
                    .toast(&format!("🎉 Daily goal reached! {min} min today"));
            }
        }
    }

    fn tick(&self) {
        self.accrue();
        if self.unsaved.get() >= 30_000 {
            self.flush_time();
        }
        self.save();
        let (page, since) = self.seen.get();
        if page != usize::MAX
            && since.elapsed() >= Duration::from_secs(4)
            && self.counted.borrow_mut().insert(page)
        {
            let (_, day) = today();
            let id = self.id.clone();
            self.app.update_quiet(|d| d.add_pages_read(&id, 1, &day));
        }
    }
}
