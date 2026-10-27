//! A small script runner for checking the UI without clicking through it: with `GBOOK_DEV` set to steps like
//! `tab:feed; wait:400; shot:/tmp/feed.png; open:first; key:Right; quit`, the app performs them in order and
//! saves PNG snapshots of its own window. Does nothing unless the variable is set.

use std::rc::Rc;
use std::time::Duration;

use gtk::prelude::*;
use gtk::{gdk, glib, graphene};

use crate::shell::{ShellRc, Tab};

pub fn run(shell: &ShellRc) {
    let Ok(script) = std::env::var("GBOOK_DEV") else {
        return;
    };
    // Report when the first frame has been drawn.
    if let Some(clock) = shell.app.window().and_then(|w| w.frame_clock()) {
        let once = std::cell::Cell::new(false);
        clock.connect_after_paint(move |_| {
            if !once.replace(true) {
                eprintln!("gbook dev: first frame after {} ms", ms());
            }
        });
    }
    let steps: Vec<String> = script
        .split(';')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    step(shell.clone(), Rc::new(steps), 0);
}

fn step(shell: ShellRc, steps: Rc<Vec<String>>, i: usize) {
    let Some(s) = steps.get(i) else { return };
    let (cmd, arg) = s.split_once(':').unwrap_or((s.as_str(), ""));
    let mut delay = 120;
    match cmd {
        "mark" => eprintln!("gbook dev: {arg} at {} ms, RSS {} MB", ms(), rss_mb()),
        "wait" => delay = arg.parse().unwrap_or(300),
        "tab" => shell.select(match arg {
            "feed" => Tab::Feed,
            "settings" => Tab::Settings,
            "search" => Tab::Search,
            _ => Tab::Library,
        }),
        "settings" => shell.open_settings(arg),
        "open" => {
            // open:<title words>[@chapter]
            let (arg, at) = match arg.split_once('@') {
                Some((a, c)) => (a, c.parse().ok().map(|c: i32| (c, 0))),
                None => (arg, None),
            };
            let id = if arg == "first" || arg.is_empty() {
                shell
                    .app
                    .data()
                    .books_by_recent()
                    .first()
                    .map(|b| b.id.clone())
            } else {
                shell
                    .app
                    .data()
                    .books
                    .iter()
                    .find(|b| b.id == arg || b.title.to_lowercase().contains(&arg.to_lowercase()))
                    .map(|b| b.id.clone())
            };
            if let Some(id) = id {
                shell.open_book(&id, at);
            }
        }
        "back" => shell.close_reader(),
        "set" => {
            // set:key=value for a few reader settings
            if let Some((k, v)) = arg.split_once('=') {
                shell.app.update(|d| match k {
                    "theme" => d.settings.theme = v.parse().unwrap_or(0),
                    "appearance" => d.settings.appearance = v.parse().unwrap_or(0),
                    "accent" => d.settings.accent = v.to_string(),
                    "page_mode" => d.settings.page_mode = v.parse().unwrap_or(0),
                    "font" => d.settings.font = v.to_string(),
                    "justify" => d.settings.justify = v == "1",
                    "tracker" => d.settings.tracker = v == "1",
                    "tracker_color" => d.settings.tracker_color = v.parse().unwrap_or(-1),
                    "tracker_unit" => d.settings.tracker_unit = v.parse().unwrap_or(0),
                    _ => {}
                });
            }
        }
        "key" => {
            if let (Some(w), Some(k)) = (shell.app.window(), gdk::Key::from_name(arg)) {
                // Deliver through the focused widget's key controllers.
                if let Some(f) = gtk::prelude::GtkWindowExt::focus(&w) {
                    let mut target: Option<gtk::Widget> = Some(f);
                    while let Some(t) = target {
                        for c in t.observe_controllers().into_iter().flatten() {
                            if let Ok(kc) = c.downcast::<gtk::EventControllerKey>() {
                                kc.emit_by_name::<bool>(
                                    "key-pressed",
                                    &[&k.to_value(), &0u32, &gdk::ModifierType::empty()],
                                );
                            }
                        }
                        target = t.parent();
                    }
                }
            }
        }
        "size" => {
            if let (Some(w), Some((a, b))) = (shell.app.window(), arg.split_once('x')) {
                w.unmaximize();
                w.set_default_size(a.parse().unwrap_or(1180), b.parse().unwrap_or(840));
            }
        }
        "click" => {
            // click:<tooltip or label> presses the first visible button that matches.
            if let Some(w) = shell.app.window() {
                if let Some(b) = find_button(w.upcast_ref(), arg) {
                    b.emit_clicked();
                } else {
                    eprintln!("gbook dev: no button {arg}");
                }
            }
        }
        "select" | "tap" => {
            // select:block:start:end / tap:block:offset in the open reader
            let n: Vec<i32> = arg.split(':').filter_map(|x| x.parse().ok()).collect();
            if let Some(r) = crate::reader::CURRENT.with(|c| c.borrow().upgrade()) {
                match (cmd, n.as_slice()) {
                    ("select", [b, s, e]) => r.dev_select(*b, *s, *e),
                    ("tap", [b, o]) => r.dev_click(*b, *o),
                    _ => eprintln!("gbook dev: bad {cmd}"),
                }
            }
        }
        "paint" => {
            // paint:N turns the tracker wheel N steps as one highlighting turn.
            if let Some(r) = crate::reader::CURRENT.with(|c| c.borrow().upgrade()) {
                r.dev_paint(arg.parse().unwrap_or(3));
            }
        }
        "popdown" => {
            if let Some(p) =
                crate::widgets::LAST_POPOVER.with(|l| l.borrow().as_ref().and_then(|w| w.upgrade()))
            {
                p.popdown();
            }
        }
        "popclick" => {
            if let Some(p) =
                crate::widgets::LAST_POPOVER.with(|l| l.borrow().as_ref().and_then(|w| w.upgrade()))
            {
                match find_button(p.upcast_ref(), arg) {
                    Some(b) => b.emit_clicked(),
                    None => eprintln!("gbook dev: no button {arg} in popover"),
                }
            }
        }
        "popshot" => {
            let pop = crate::widgets::LAST_POPOVER
                .with(|l| l.borrow().as_ref().and_then(|w| w.upgrade()));
            match pop {
                Some(p) => {
                    if let Err(e) = snapshot_widget(p.upcast_ref(), arg) {
                        eprintln!("gbook dev: popover snapshot failed: {e}");
                    }
                }
                None => eprintln!("gbook dev: no popover"),
            }
        }
        "shot" => {
            if let Some(w) = shell.app.window()
                && let Err(e) = snapshot(&w, arg)
            {
                eprintln!("gbook dev: snapshot failed: {e}");
            }
        }
        "quit" => {
            if let Some(w) = shell.app.window() {
                w.close();
            }
            return;
        }
        other => eprintln!("gbook dev: unknown step {other}"),
    }
    glib::timeout_add_local_once(Duration::from_millis(delay), move || {
        step(shell, steps, i + 1)
    });
}

/// The last matching button in tree order: overlays such as sheets come after the page beneath them.
fn find_button(root: &gtk::Widget, name: &str) -> Option<gtk::Button> {
    let mut found = None;
    let mut child = root.first_child();
    while let Some(c) = child {
        if c.is_visible() && c.is_mapped() {
            if let Some(b) = c.downcast_ref::<gtk::Button>() {
                let tip = b.tooltip_text().map(|t| t.to_string()).unwrap_or_default();
                let lbl = b.label().map(|t| t.to_string()).unwrap_or_default();
                if tip.starts_with(name) || lbl == name {
                    found = Some(b.clone());
                }
            }
            if let Some(b) = find_button(&c, name) {
                found = Some(b);
            }
        }
        child = c.next_sibling();
    }
    found
}

fn snapshot_widget(w: &gtk::Widget, path: &str) -> Result<(), String> {
    let renderer = w.native().and_then(|n| n.renderer()).ok_or("no renderer")?;
    let (width, height) = (w.width() as f32, w.height() as f32);
    let paintable = gtk::WidgetPaintable::new(Some(w));
    let snap = gtk::Snapshot::new();
    paintable.snapshot(&snap, width as f64, height as f64);
    let node = snap.to_node().ok_or("empty")?;
    let tex = renderer.render_texture(&node, Some(&graphene::Rect::new(0.0, 0.0, width, height)));
    tex.save_to_png(path).map_err(|e| e.to_string())
}

fn snapshot(w: &gtk::ApplicationWindow, path: &str) -> Result<(), String> {
    let renderer = w.renderer().ok_or("no renderer")?;
    let (width, height) = (w.width() as f32, w.height() as f32);
    let paintable = gtk::WidgetPaintable::new(Some(w));
    let snap = gtk::Snapshot::new();
    paintable.snapshot(&snap, width as f64, height as f64);
    let node = snap.to_node().ok_or("empty window")?;
    let tex = renderer.render_texture(&node, Some(&graphene::Rect::new(0.0, 0.0, width, height)));
    tex.save_to_png(path).map_err(|e| e.to_string())
}

fn ms() -> u128 {
    crate::STARTED
        .get()
        .map(|t| t.elapsed().as_millis())
        .unwrap_or(0)
}

/// Resident memory of this process.
fn rss_mb() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("VmRSS:"))
                .and_then(|l| l.split_whitespace().nth(1)?.parse::<u64>().ok())
        })
        .map(|kb| kb / 1024)
        .unwrap_or(0)
}
