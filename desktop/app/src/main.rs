//! GBook for the Linux desktop: a GTK 4 eReader that shares its library format, look and features with the
//! Android app.

// A windowed app on Windows: no console behind it in release builds.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]
// GTK signal handlers make for long closure types; naming each one wouldn't make them clearer.
#![allow(clippy::type_complexity)]

mod badge;
mod booksheet;
mod devtools;
mod drive;
mod feed;
mod import;
mod library;
mod media;
mod pageview;
mod pdfview;
mod reader;
mod reader_options;
mod search;
mod settings;
mod sheet;
mod shell;
mod sidebar;
mod state;
mod sync;
mod theme;
mod tracker;
mod widgets;

use std::ffi::CString;
use std::path::{Path, PathBuf};

use gbook_core::store::Paths;
use gtk::prelude::*;
use gtk::{gdk, gio, glib};

const APP_ID: &str = "io.github.gsweya.GBook";

/// Folder holding the app's fonts and icons: `$GBOOK_DATA`, else `<prefix>/share/gbook` next to the binary,
/// else the source tree when running from cargo.
pub fn assets_dir() -> PathBuf {
    if let Some(p) = std::env::var_os("GBOOK_DATA") {
        return PathBuf::from(p);
    }
    if let Ok(exe) = std::env::current_exe()
        && let Some(prefix) = exe.parent().and_then(Path::parent)
    {
        let p = prefix.join("share/gbook");
        if p.join("icons").is_dir() {
            return p;
        }
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("data")
}

/// Windows: point Pango at fontconfig (bundled with GTK there) instead of the system font list, so the app's
/// own fonts load the same way they do on Linux.
#[cfg(windows)]
fn prepare_platform() {
    let set = |k: &str, v: &std::ffi::OsStr| {
        if std::env::var_os(k).is_none() {
            // SAFETY: called first thing in main, before any other thread exists.
            unsafe { std::env::set_var(k, v) };
        }
    };
    set("PANGOCAIRO_BACKEND", std::ffi::OsStr::new("fc"));
    if let Ok(exe) = std::env::current_exe()
        && let Some(prefix) = exe.parent().and_then(Path::parent)
    {
        let fonts = prefix.join("etc").join("fonts");
        if fonts.join("fonts.conf").is_file() {
            set("FONTCONFIG_PATH", fonts.as_os_str());
            set("FONTCONFIG_FILE", fonts.join("fonts.conf").as_os_str());
        }
    }
}

#[cfg(not(windows))]
fn prepare_platform() {}

#[link(name = "fontconfig")]
unsafe extern "C" {
    fn FcConfigAppFontAddDir(config: *mut std::ffi::c_void, dir: *const std::ffi::c_char) -> i32;
}

/// Makes the bundled reading and UI fonts available to Pango without installing them system-wide.
fn register_fonts(assets: &Path) {
    let mut dirs = vec![assets.join("fonts")];
    // From the source tree, share the Android app's fonts instead of keeping a second copy.
    dirs.push(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../gbook/app/src/main/res/font"));
    if let Some(dir) = dirs.into_iter().find(|d| d.is_dir())
        && let Ok(c) = CString::new(dir.to_string_lossy().as_bytes())
    {
        // SAFETY: a null config means fontconfig's current configuration; `c` outlives the call.
        unsafe { FcConfigAppFontAddDir(std::ptr::null_mut(), c.as_ptr()) };
    }
}

/// Follows the desktop's light/dark preference through the settings portal, live.
fn watch_color_scheme(app: &state::AppRc) {
    let Ok(bus) = gio::bus_get_sync(gio::BusType::Session, gio::Cancellable::NONE) else {
        return;
    };
    let read = |v: &glib::Variant| -> Option<bool> {
        // ReadOne returns (v); the value is a u32: 1 prefers dark.
        let inner = v.child_value(0);
        let inner = inner.as_variant().unwrap_or(inner);
        inner.get::<u32>().map(|n| n == 1)
    };
    if let Ok(v) = bus.call_sync(
        Some("org.freedesktop.portal.Desktop"),
        "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.Settings",
        "ReadOne",
        Some(&("org.freedesktop.appearance", "color-scheme").to_variant()),
        None,
        gio::DBusCallFlags::NONE,
        300,
        gio::Cancellable::NONE,
    ) && let Some(d) = read(&v)
    {
        app.system_dark.set(d);
    }
    let app2 = app.clone();
    #[allow(deprecated)]
    bus.signal_subscribe(
        Some("org.freedesktop.portal.Desktop"),
        Some("org.freedesktop.portal.Settings"),
        Some("SettingChanged"),
        Some("/org/freedesktop/portal/desktop"),
        None,
        gio::DBusSignalFlags::NONE,
        move |_, _, _, _, _, params| {
            let (ns, key): (String, String) = (
                params.child_value(0).get().unwrap_or_default(),
                params.child_value(1).get().unwrap_or_default(),
            );
            if ns == "org.freedesktop.appearance" && key == "color-scheme" {
                let v = params.child_value(2);
                let v = v.as_variant().unwrap_or(v);
                if let Some(n) = v.get::<u32>() {
                    app2.system_dark.set(n == 1);
                    app2.apply_theme();
                    app2.update(|_| ());
                }
            }
        },
    );
}

/// Startup timing, printed when the dev tools are on.
fn trace(what: &str) {
    if std::env::var_os("GBOOK_DEV").is_some() {
        eprintln!(
            "gbook dev: {what} after {} ms",
            STARTED.get().map(|t| t.elapsed().as_millis()).unwrap_or(0)
        );
    }
}

/// When the process started, for the dev tools' timing marks.
pub static STARTED: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();

#[cfg(all(target_os = "linux", target_env = "gnu"))]
mod malloc {
    unsafe extern "C" {
        fn mallopt(param: i32, value: i32) -> i32;
        fn malloc_trim(pad: usize) -> i32;
    }
    const M_ARENA_MAX: i32 = -8;

    /// glibc gives every decoding thread its own heap and keeps what they free; two heaps are plenty here.
    pub fn limit_arenas() {
        // SAFETY: plain glibc tuning call, made before any threads start.
        unsafe { mallopt(M_ARENA_MAX, 2) };
    }

    /// Hands freed memory back to the system, e.g. after closing a book.
    pub fn trim() {
        // SAFETY: malloc_trim only releases free memory.
        unsafe { malloc_trim(0) };
    }
}

#[cfg(not(all(target_os = "linux", target_env = "gnu")))]
mod malloc {
    pub fn limit_arenas() {}
    pub fn trim() {}
}

pub use malloc::trim as release_memory;

fn main() -> glib::ExitCode {
    STARTED.get_or_init(std::time::Instant::now);
    prepare_platform();
    malloc::limit_arenas();
    let assets = assets_dir();
    register_fonts(&assets);
    trace("fonts registered");

    let gapp = gtk::Application::builder()
        .application_id(APP_ID)
        .flags(if std::env::var_os("GBOOK_DEV").is_some() {
            // Scripted runs stay separate from a GBook the user has open.
            gio::ApplicationFlags::HANDLES_OPEN | gio::ApplicationFlags::NON_UNIQUE
        } else {
            gio::ApplicationFlags::HANDLES_OPEN
        })
        .build();
    let state: std::rc::Rc<std::cell::RefCell<Option<state::AppRc>>> = Default::default();

    let st = state.clone();
    let assets2 = assets.clone();
    gapp.connect_startup(move |_| {
        let display = gdk::Display::default().expect("a display");
        gtk::Window::set_default_icon_name(APP_ID);
        gtk::IconTheme::for_display(&display).add_search_path(assets2.join("icons"));
        let app = state::App::new(Paths::default_location());
        gtk::style_context_add_provider_for_display(
            &display,
            &app.css,
            gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
        );
        if let Some(s) = gtk::Settings::default() {
            s.set_gtk_font_name(Some(&format!("{} Medium 11", theme::SANS)));
        }
        trace("library loaded");
        watch_color_scheme(&app);
        trace("colour scheme read");
        app.apply_theme();
        trace("stylesheet applied");
        *st.borrow_mut() = Some(app);
    });

    let st = state.clone();
    gapp.connect_activate(move |gapp| {
        let app = st.borrow().clone().expect("started");
        match app.window() {
            Some(w) => w.present(),
            None => {
                shell::build(gapp, &app);
                trace("window built");
                import::add_starter(&app);
                sync::start(&app);
            }
        }
    });

    let st = state.clone();
    gapp.connect_open(move |gapp, files, _| {
        let app = st.borrow().clone().expect("started");
        if app.window().is_none() {
            shell::build(gapp, &app);
            import::add_starter(&app);
            sync::start(&app);
        }
        if let Some(w) = app.window() {
            w.present();
        }
        let paths: Vec<PathBuf> = files.iter().filter_map(|f| f.path()).collect();
        import::import_paths(&app, paths, true);
    });

    let st = state.clone();
    gapp.connect_shutdown(move |_| {
        if let Some(app) = st.borrow().as_ref() {
            app.flush();
        }
    });
    gapp.run()
}
