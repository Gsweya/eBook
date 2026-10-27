//! The reader's text view: a GtkTextView that also draws highlights the way the phone does, a soft marker
//! behind the words or a thick rounded underline, plus the reaction emoji at the end of a highlight.

use std::cell::{Cell, RefCell};

use gtk::prelude::*;
use gtk::subclass::prelude::*;
use gtk::{gdk, glib, graphene, gsk, pango};

/// A highlight (or the focus tracker's marker) over buffer offsets `start..end`: a soft fill behind the words,
/// a rounded underline, or both.
#[derive(Clone)]
pub struct Mark {
    pub start: i32,
    pub end: i32,
    pub fill: Option<gdk::RGBA>,
    pub underline: Option<gdk::RGBA>,
    /// A thinner underline, for the tracker.
    pub thin: bool,
    pub reaction: Option<String>,
}

mod imp {
    use super::*;

    #[derive(Default)]
    pub struct PageView {
        pub marks: RefCell<Vec<Mark>>,
        /// Body font size in pixels, which sizes the marker, underline and emoji.
        pub px: Cell<f32>,
    }

    #[glib::object_subclass]
    impl ObjectSubclass for PageView {
        const NAME: &'static str = "GBookPageView";
        type Type = super::PageView;
        type ParentType = gtk::TextView;
    }

    impl ObjectImpl for PageView {}
    impl WidgetImpl for PageView {}

    impl TextViewImpl for PageView {
        fn snapshot_layer(&self, layer: gtk::TextViewLayer, snapshot: gtk::Snapshot) {
            self.parent_snapshot_layer(layer, snapshot.clone());
            let view = self.obj();
            let marks = self.marks.borrow();
            if marks.is_empty() {
                return;
            }
            let px = self.px.get().max(8.0);
            let visible = view.visible_rect();
            let (top, bottom) = (visible.y(), visible.y() + visible.height());
            let buf = view.buffer();
            for m in marks.iter() {
                let mut it = buf.iter_at_offset(m.start);
                let end = buf.iter_at_offset(m.end);
                // Skip highlights entirely off screen.
                let first = view.iter_location(&it);
                let last = view.iter_location(&end);
                if last.y() + last.height() < top || first.y() > bottom {
                    continue;
                }
                if layer == gtk::TextViewLayer::BelowText {
                    // One rounded bar per display line the highlight covers.
                    let mut guard = 0;
                    while it.offset() < end.offset() && guard < 10_000 {
                        guard += 1;
                        let mut line_end = it;
                        view.forward_display_line_end(&mut line_end);
                        let seg_end = if line_end.offset() < end.offset()
                            && line_end.offset() > it.offset()
                        {
                            line_end
                        } else {
                            end
                        };
                        let a = view.iter_location(&it);
                        let b = view.iter_location(&seg_end);
                        let x0 = a.x() as f32;
                        let x1 = if b.y() > a.y() + 2 {
                            x0.max(view.iter_location(&line_end).x() as f32)
                        } else {
                            b.x() as f32
                        };
                        let mid = a.y() as f32 + a.height() as f32 / 2.0;
                        if x1 > x0 && (a.y() + a.height()) >= top && a.y() <= bottom {
                            let mut bars = Vec::new();
                            if let Some(c) = m.fill {
                                bars.push((
                                    graphene::Rect::new(
                                        x0 - 1.0,
                                        mid - px * 0.66,
                                        x1 - x0 + 2.0,
                                        px * 1.32,
                                    ),
                                    3.0,
                                    c,
                                ));
                            }
                            if let Some(c) = m.underline {
                                let t = if m.thin {
                                    (px * 0.1).clamp(1.5, 3.0)
                                } else {
                                    (px * 0.13).clamp(2.0, 4.0)
                                };
                                bars.push((
                                    graphene::Rect::new(x0, mid + px * 0.66, x1 - x0, t),
                                    t / 2.0,
                                    c,
                                ));
                            }
                            for (rect, radius, color) in bars {
                                let r = graphene::Size::new(radius, radius);
                                snapshot
                                    .push_rounded_clip(&gsk::RoundedRect::new(rect, r, r, r, r));
                                snapshot.append_color(&color, &rect);
                                snapshot.pop();
                            }
                        }
                        if seg_end.offset() >= end.offset() {
                            break;
                        }
                        it = seg_end;
                        if !view.forward_display_line(&mut it) {
                            break;
                        }
                    }
                } else if let Some(emoji) = &m.reaction {
                    // The reaction sits like a small superscript at the end of the highlight.
                    let layout = view.create_pango_layout(Some(emoji));
                    let mut fd = pango::FontDescription::new();
                    fd.set_absolute_size((px * 0.8) as f64 * pango::SCALE as f64);
                    layout.set_font_description(Some(&fd));
                    let (w, h) = layout.pixel_size();
                    let mid = last.y() as f32 + last.height() as f32 / 2.0;
                    snapshot.save();
                    snapshot.translate(&graphene::Point::new(
                        last.x() as f32 - w as f32 * 0.15,
                        mid - px * 0.66 - h as f32 * 0.55,
                    ));
                    snapshot.append_layout(&layout, &gdk::RGBA::BLACK);
                    snapshot.restore();
                }
            }
        }
    }
}

glib::wrapper! {
    pub struct PageView(ObjectSubclass<imp::PageView>)
        @extends gtk::TextView, gtk::Widget,
        @implements gtk::Accessible, gtk::AccessibleText, gtk::Buildable, gtk::ConstraintTarget, gtk::Scrollable;
}

impl PageView {
    pub fn new() -> PageView {
        glib::Object::new()
    }

    pub fn set_marks(&self, marks: Vec<Mark>, px: f32) {
        *self.imp().marks.borrow_mut() = marks;
        self.imp().px.set(px);
        self.queue_draw();
    }
}
