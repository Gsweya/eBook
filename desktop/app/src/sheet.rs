//! Sheets: the phone's bottom sheets become a card centred over the dimmed window. Escape, the close button
//! or a click on the backdrop dismisses them.

use std::cell::RefCell;
use std::rc::Rc;
use std::time::Duration;

use gtk::prelude::*;
use gtk::{gdk, glib};

use crate::state::AppRc;
use crate::widgets::{circle_button, hbox, label, vbox};

pub type Close = Rc<dyn Fn()>;

/// Shows `content` in a sheet `width` pixels wide with an optional title; returns a function that closes it.
pub fn present(
    app: &AppRc,
    title: Option<&str>,
    content: &impl IsA<gtk::Widget>,
    width: i32,
) -> Close {
    present_with(app, title, content, width, || {})
}

pub fn present_with(
    app: &AppRc,
    title: Option<&str>,
    content: &impl IsA<gtk::Widget>,
    width: i32,
    on_close: impl Fn() + 'static,
) -> Close {
    let Some(host) = app.sheet_host.borrow().clone() else {
        return Rc::new(|| {});
    };
    let layer = gtk::Overlay::new();
    let scrim = gtk::Box::new(gtk::Orientation::Vertical, 0);
    scrim.add_css_class("scrim");
    scrim.set_hexpand(true);
    scrim.set_vexpand(true);
    layer.set_child(Some(&scrim));

    let panel = vbox(0);
    panel.add_css_class("sheet-panel");
    panel.set_halign(gtk::Align::Center);
    panel.set_valign(gtk::Align::Center);
    panel.set_margin_top(24);
    panel.set_margin_bottom(24);
    panel.set_margin_start(16);
    panel.set_margin_end(16);
    panel.set_overflow(gtk::Overflow::Hidden);
    let head = hbox(8);
    head.add_css_class("sheet-head");
    if let Some(t) = title {
        let l = label(t, &["sheet-title"]);
        l.set_hexpand(true);
        head.append(&l);
    } else {
        head.append(&crate::widgets::spacer());
    }
    let close_btn = circle_button("close", "Close (Esc)");
    close_btn.add_css_class("small");
    head.append(&close_btn);
    panel.append(&head);

    let scroller = gtk::ScrolledWindow::new();
    scroller.set_hscrollbar_policy(gtk::PolicyType::Never);
    scroller.set_propagate_natural_height(true);
    scroller.set_child(Some(content));
    scroller.set_size_request(width, -1);
    panel.append(&scroller);
    // Keep the sheet inside the window however tall its content is.
    let win_h = app.window().map(|w| w.height()).unwrap_or(800);
    scroller.set_max_content_height((win_h - 140).max(240));
    layer.add_overlay(&panel);

    let rev = gtk::Revealer::new();
    rev.set_transition_type(gtk::RevealerTransitionType::Crossfade);
    rev.set_transition_duration(160);
    rev.set_child(Some(&layer));
    host.add_overlay(&rev);
    rev.set_reveal_child(true);

    let closed = Rc::new(RefCell::new(false));
    let on_close = Rc::new(on_close);
    let close: Close = {
        let rev = rev.clone();
        let host = host.clone();
        Rc::new(move || {
            if closed.replace(true) {
                return;
            }
            on_close();
            rev.set_reveal_child(false);
            let (r, h) = (rev.clone(), host.clone());
            glib::timeout_add_local_once(Duration::from_millis(200), move || h.remove_overlay(&r));
        })
    };
    let c = close.clone();
    close_btn.connect_clicked(move |_| c());
    let click = gtk::GestureClick::new();
    let c = close.clone();
    click.connect_released(move |_, _, _, _| c());
    scrim.add_controller(click);
    let keys = gtk::EventControllerKey::new();
    keys.set_propagation_phase(gtk::PropagationPhase::Capture);
    let c = close.clone();
    keys.connect_key_pressed(move |_, key, _, _| {
        if key == gdk::Key::Escape {
            c();
            glib::Propagation::Stop
        } else {
            glib::Propagation::Proceed
        }
    });
    layer.add_controller(keys);
    layer.set_focusable(true);
    let l2 = layer.clone();
    glib::idle_add_local_once(move || {
        l2.grab_focus();
    });
    close
}

/// A sheet whose content `build` fills, and fills again whenever the library changes while it's open.
pub fn present_live(
    app: &AppRc,
    title: Option<&str>,
    width: i32,
    build: impl Fn(&gtk::Box, &Close) + 'static,
) -> Close {
    let content = vbox(0);
    let holder: Rc<RefCell<Option<Close>>> = Rc::default();
    let build = Rc::new(build);
    let render = {
        let (content, holder, build) = (content.clone(), holder.clone(), build.clone());
        move || {
            let Some(close) = holder.borrow().clone() else {
                return;
            };
            crate::widgets::clear(&content);
            build(&content, &close);
        }
    };
    let listener = Rc::new(std::cell::Cell::new(usize::MAX));
    let (a, l) = (app.clone(), listener.clone());
    let close = present_with(app, title, &content, width, move || {
        a.remove_listener(l.get())
    });
    *holder.borrow_mut() = Some(close.clone());
    render();
    listener.set(app.on_change(render));
    close
}
