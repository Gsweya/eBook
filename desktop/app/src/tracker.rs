//! The focus tracker's wheel: a dial on the reader's right edge, drawn as a cylinder seen edge-on, as on the
//! phone. Scrolling over it, dragging its ridges, or clicking it walks a marker through the text a word or a
//! sentence at a time; with a highlight colour chosen, every turn also highlights what the marker passes.

use std::cell::Cell;
use std::f64::consts::PI;
use std::rc::Rc;
use std::time::Duration;

use gtk::prelude::*;
use gtk::{gdk, glib};

use crate::state::AppRc;
use crate::theme::{self, HIGHLIGHT_COLORS};

/// What the wheel asks of the reader.
pub struct WheelInput {
    /// Step the marker forward or back; returns false at the end of the chapter.
    pub step: Box<dyn Fn(bool) -> bool>,
    /// The wheel was grabbed or started turning: start highlighting from the marker.
    pub press: Box<dyn Fn()>,
    /// The turn is over: save what was highlighted.
    pub release: Box<dyn Fn()>,
}

pub struct Wheel {
    pub widget: gtk::Box,
    area: gtk::DrawingArea,
    turned: Rc<Cell<f64>>,
    held: Rc<Cell<bool>>,
}

impl Wheel {
    pub fn new(app: &AppRc, input: WheelInput) -> Wheel {
        let input = Rc::new(input);
        let widget = gtk::Box::new(gtk::Orientation::Vertical, 0);
        widget.set_size_request(56, 244);
        widget.set_halign(gtk::Align::End);
        widget.set_margin_end(10);
        widget.set_tooltip_text(Some(
            "Focus tracker: scroll, drag or click to move the marker (. and , keys too)",
        ));
        let area = gtk::DrawingArea::new();
        area.add_css_class("tracker-wheel");
        area.set_content_width(36);
        area.set_content_height(220);
        area.set_halign(gtk::Align::Center);
        area.set_valign(gtk::Align::Center);
        area.set_vexpand(true);
        widget.append(&area);
        let turned = Rc::new(Cell::new(0.0));
        let held = Rc::new(Cell::new(false));

        let (a, t, h) = (app.clone(), turned.clone(), held.clone());
        area.set_draw_func(move |_, cr, w, hgt| {
            draw(&a, cr, w as f64, hgt as f64, t.get(), h.get())
        });

        // Mouse wheel and touchpad: one step per notch, or per stretch of smooth scrolling.
        let scroll = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::VERTICAL);
        let acc = Rc::new(Cell::new(0.0f64));
        let idle: Rc<Cell<u32>> = Rc::new(Cell::new(0));
        let (inp, t, ar, a2) = (input.clone(), turned.clone(), area.clone(), app.clone());
        scroll.connect_scroll(move |c, _, dy| {
            if idle.get() == 0 {
                (inp.press)();
            }
            let generation = idle.get().wrapping_add(1).max(1);
            idle.set(generation);
            let smooth = c.unit() == gdk::ScrollUnit::Surface;
            let step_px = if a2.data().settings.tracker_unit == 1 {
                40.0
            } else {
                16.0
            };
            if smooth {
                acc.set(acc.get() + dy);
                t.set(t.get() + dy);
                while acc.get() >= step_px {
                    acc.set(acc.get() - step_px);
                    (inp.step)(true);
                }
                while acc.get() <= -step_px {
                    acc.set(acc.get() + step_px);
                    (inp.step)(false);
                }
            } else {
                let n = dy.round() as i32;
                for _ in 0..n.unsigned_abs().max(1) {
                    (inp.step)(dy > 0.0);
                }
                t.set(t.get() + dy.signum() * step_px);
            }
            ar.queue_draw();
            // A turn ends once the wheel has been still for a moment.
            let (inp2, idle2) = (inp.clone(), idle.clone());
            glib::timeout_add_local_once(Duration::from_millis(700), move || {
                if idle2.get() == generation {
                    idle2.set(0);
                    (inp2.release)();
                }
            });
            glib::Propagation::Stop
        });
        widget.add_controller(scroll);

        // Dragging the ridges turns it; a click without a drag steps once.
        let drag = gtk::GestureDrag::new();
        // `last`: offset at the last step; `prev`: offset at the last update, for drawing the turn.
        let last = Rc::new(Cell::new(0.0f64));
        let prev = Rc::new(Cell::new(0.0f64));
        let moved = Rc::new(Cell::new(false));
        let (inp, h, ar, l, p, m) = (
            input.clone(),
            held.clone(),
            area.clone(),
            last.clone(),
            prev.clone(),
            moved.clone(),
        );
        drag.connect_drag_begin(move |_, _, _| {
            h.set(true);
            l.set(0.0);
            p.set(0.0);
            m.set(false);
            (inp.press)();
            ar.queue_draw();
        });
        let (inp, t, ar, l, m, a2) = (
            input.clone(),
            turned.clone(),
            area.clone(),
            last.clone(),
            moved.clone(),
            app.clone(),
        );
        drag.connect_drag_update(move |_, _, oy| {
            let step_px = if a2.data().settings.tracker_unit == 1 {
                40.0
            } else {
                16.0
            };
            let dy = oy - l.get();
            t.set(t.get() + oy - prev.get());
            prev.set(oy);
            if dy.abs() >= step_px {
                let n = (dy / step_px).trunc() as i32;
                for _ in 0..n.abs() {
                    (inp.step)(n > 0);
                }
                l.set(l.get() + n as f64 * step_px);
                m.set(true);
            }
            ar.queue_draw();
        });
        let (inp, h, ar, m, t, a2) = (
            input.clone(),
            held.clone(),
            area.clone(),
            moved.clone(),
            turned.clone(),
            app.clone(),
        );
        drag.connect_drag_end(move |_, _, _| {
            // A plain click steps once; with a colour armed it highlights just the current word.
            if !m.get() && a2.data().settings.tracker_color < 0 {
                (inp.step)(true);
                t.set(t.get() + 16.0);
            }
            h.set(false);
            (inp.release)();
            ar.queue_draw();
        });
        widget.add_controller(drag);

        Wheel {
            widget,
            area,
            turned,
            held,
        }
    }

    /// Redraws the ridges after the marker moved by keyboard, as if the wheel had turned one step.
    pub fn nudge(&self, forward: bool) {
        self.turned
            .set(self.turned.get() + if forward { 16.0 } else { -16.0 });
        self.area.queue_draw();
    }

    pub fn set_held(&self, held: bool) {
        self.held.set(held);
        self.area.queue_draw();
    }
}

fn draw(app: &AppRc, cr: &gtk::cairo::Context, w: f64, h: f64, turned: f64, held: bool) {
    let s = app.data().settings.clone();
    let (page, ink) = theme::page_colors(&s, app.dark());
    let notch = if s.tracker_color >= 0 {
        HIGHLIGHT_COLORS[s.tracker_color as usize % HIGHLIGHT_COLORS.len()]
    } else {
        app.colors().accent
    };
    let rgb = |c: theme::Rgb, a: f64| {
        (
            c.0 as f64 / 255.0,
            c.1 as f64 / 255.0,
            c.2 as f64 / 255.0,
            a,
        )
    };
    let rr = w / 2.0;
    let capsule = |cr: &gtk::cairo::Context| {
        cr.new_path();
        cr.arc(rr, rr, rr, PI, 0.0);
        cr.arc(rr, h - rr, rr, 0.0, PI);
        cr.close_path();
    };
    // The dial, in the page's colour, shaded at the ends so it reads as a cylinder.
    capsule(cr);
    let (r, g, b, _) = rgb(page, 1.0);
    cr.set_source_rgb(r, g, b);
    let _ = cr.fill_preserve();
    let grad = gtk::cairo::LinearGradient::new(0.0, 0.0, 0.0, h);
    let (ir, ig, ib, _) = rgb(ink, 1.0);
    for (o, a) in [(0.0, 0.10), (0.3, 0.02), (0.7, 0.02), (1.0, 0.10)] {
        grad.add_color_stop_rgba(o, ir, ig, ib, a);
    }
    let _ = cr.set_source(&grad);
    let _ = cr.fill_preserve();
    cr.set_source_rgba(ir, ig, ib, if held { 0.24 } else { 0.13 });
    cr.set_line_width(1.0);
    let _ = cr.stroke();
    // Ridges every 12°, moving with the turn, thinner and fainter towards the edges.
    let radius = h / 2.0 - 10.0;
    let cy = h / 2.0;
    let gap = 12f64.to_radians();
    let phase = (turned / radius).rem_euclid(gap);
    let mut a = -PI / 2.0 + phase - gap;
    cr.set_line_cap(gtk::cairo::LineCap::Round);
    while a < PI / 2.0 {
        if a > -PI / 2.0 {
            let depth = a.cos();
            let y = cy + radius * a.sin();
            let inset = 9.0 + (1.0 - depth) * 4.0;
            cr.set_source_rgba(ir, ig, ib, 0.08 + 0.32 * depth);
            cr.set_line_width(1.0 + 1.5 * depth);
            cr.move_to(inset, y);
            cr.line_to(w - inset, y);
            let _ = cr.stroke();
        }
        a += gap;
    }
    // Centre notches: where the marker reads from, in the highlight colour when the wheel paints.
    let (nr, ng, nb, _) = rgb(notch, 1.0);
    cr.set_source_rgba(nr, ng, nb, if held { 1.0 } else { 0.85 });
    cr.set_line_width(3.0);
    for (x0, x1) in [(3.0, 7.0), (w - 7.0, w - 3.0)] {
        cr.move_to(x0, cy);
        cr.line_to(x1, cy);
        let _ = cr.stroke();
    }
}
