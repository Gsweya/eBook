//! The single source of truth for the running app: library data, where it lives on disk, and who to tell when
//! it changes. Saving is debounced and written atomically; views re-render on change notifications.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;

use gbook_core::store::{self, Paths};
use gbook_core::{BookContent, LibraryData};
use gtk::prelude::*;
use gtk::{gdk, glib};

use crate::theme::{self, Colors};

pub struct App {
    pub paths: Paths,
    data: RefCell<LibraryData>,
    listeners: RefCell<Vec<(usize, Rc<dyn Fn()>)>>,
    next_listener: Cell<usize>,
    notify_queued: Cell<bool>,
    save_source: RefCell<Option<glib::SourceId>>,
    textures: RefCell<HashMap<(PathBuf, i32), gdk::Texture>>,
    content: RefCell<Vec<(String, Rc<BookContent>)>>,
    pub css: gtk::CssProvider,
    pub system_dark: Cell<bool>,
    colors: Cell<Colors>,
    css_key: RefCell<String>,
    pub window: RefCell<Option<gtk::ApplicationWindow>>,
    /// Root overlay that sheets are shown in.
    pub sheet_host: RefCell<Option<gtk::Overlay>>,
    pub toast: RefCell<Option<Rc<dyn Fn(&str, bool)>>>,
    /// Opens a book in the reader; set by the shell.
    pub open_book: RefCell<Option<Rc<dyn Fn(&str, Option<(i32, i32)>)>>>,
}

pub type AppRc = Rc<App>;

impl App {
    pub fn new(paths: Paths) -> AppRc {
        let data = store::load(&paths);
        let p = theme::palette(&data.settings.accent);
        Rc::new(App {
            paths,
            data: RefCell::new(data),
            listeners: RefCell::new(Vec::new()),
            next_listener: Cell::new(0),
            notify_queued: Cell::new(false),
            save_source: RefCell::new(None),
            textures: RefCell::new(HashMap::new()),
            content: RefCell::new(Vec::new()),
            css: gtk::CssProvider::new(),
            system_dark: Cell::new(false),
            colors: Cell::new(theme::colors(p, false, false)),
            css_key: RefCell::new(String::new()),
            window: RefCell::new(None),
            sheet_host: RefCell::new(None),
            toast: RefCell::new(None),
            open_book: RefCell::new(None),
        })
    }

    /// Read-only access to the library.
    pub fn data(&self) -> std::cell::Ref<'_, LibraryData> {
        self.data.borrow()
    }

    /// Changes the library, saves it soon and tells every view.
    pub fn update<R>(self: &Rc<Self>, f: impl FnOnce(&mut LibraryData) -> R) -> R {
        let r = f(&mut self.data.borrow_mut());
        self.schedule_save();
        self.queue_notify();
        self.apply_theme();
        r
    }

    /// Changes the library and saves it soon without re-rendering views: for reading position and time.
    pub fn update_quiet<R>(self: &Rc<Self>, f: impl FnOnce(&mut LibraryData) -> R) -> R {
        let r = f(&mut self.data.borrow_mut());
        self.schedule_save();
        r
    }

    /// Calls `f` after every change to the library; returns an id for `remove_listener`.
    pub fn on_change(&self, f: impl Fn() + 'static) -> usize {
        let id = self.next_listener.get();
        self.next_listener.set(id + 1);
        self.listeners.borrow_mut().push((id, Rc::new(f)));
        id
    }

    pub fn remove_listener(&self, id: usize) {
        self.listeners.borrow_mut().retain(|(k, _)| *k != id);
    }

    fn queue_notify(self: &Rc<Self>) {
        if self.notify_queued.replace(true) {
            return;
        }
        let app = self.clone();
        glib::idle_add_local_once(move || {
            app.notify_queued.set(false);
            let ls: Vec<_> = app
                .listeners
                .borrow()
                .iter()
                .map(|(_, f)| f.clone())
                .collect();
            for l in ls {
                l();
            }
        });
    }

    fn schedule_save(self: &Rc<Self>) {
        if let Some(id) = self.save_source.borrow_mut().take() {
            id.remove();
        }
        let app = self.clone();
        let id = glib::timeout_add_local_once(Duration::from_millis(400), move || {
            app.save_source.borrow_mut().take();
            app.save_now();
        });
        *self.save_source.borrow_mut() = Some(id);
    }

    /// Writes pending changes immediately, e.g. when the window closes.
    pub fn flush(&self) {
        if let Some(id) = self.save_source.borrow_mut().take() {
            id.remove();
            self.save_now();
        }
    }

    fn save_now(&self) {
        if let Err(e) = store::save(&self.paths, &self.data.borrow()) {
            eprintln!("gbook: couldn't save the library: {e}");
            self.toast(&format!("Couldn't save: {e}"));
        }
    }

    pub fn toast(&self, msg: &str) {
        let t = self.toast.borrow().clone();
        if let Some(t) = t {
            t(msg, false);
        }
    }

    /// Shows `msg` with a spinner until `busy(None)`… or replaced by a toast.
    pub fn busy(&self, msg: Option<&str>) {
        let t = self.toast.borrow().clone();
        if let Some(t) = t {
            match msg {
                Some(m) => t(m, true),
                None => t("", false),
            }
        }
    }

    pub fn colors(&self) -> Colors {
        self.colors.get()
    }

    pub fn dark(&self) -> bool {
        match self.data.borrow().settings.appearance {
            1 => false,
            2 => true,
            _ => self.system_dark.get(),
        }
    }

    /// Regenerates the stylesheet when the accent, mode or pure black setting changed.
    pub fn apply_theme(&self) {
        let (accent, amoled) = {
            let s = &self.data.borrow().settings;
            (s.accent.clone(), s.amoled)
        };
        let dark = self.dark();
        let key = format!("{accent}/{dark}/{amoled}");
        if *self.css_key.borrow() == key {
            return;
        }
        *self.css_key.borrow_mut() = key;
        let c = theme::colors(theme::palette(&accent), dark, amoled);
        self.colors.set(c);
        self.css.load_from_string(&theme::css(&c));
        if let Some(s) = gtk::Settings::default() {
            s.set_gtk_application_prefer_dark_theme(dark);
        }
    }

    /// Book text, from a small in-memory cache of recently opened books.
    pub fn content(&self, id: &str) -> Option<Rc<BookContent>> {
        if let Some((_, c)) = self.content.borrow().iter().find(|(k, _)| k == id) {
            return Some(c.clone());
        }
        let c = Rc::new(store::load_content(&self.paths, id)?);
        let mut cache = self.content.borrow_mut();
        cache.insert(0, (id.to_string(), c.clone()));
        cache.truncate(3);
        Some(c)
    }

    pub fn forget_content(&self, id: &str) {
        self.content.borrow_mut().retain(|(k, _)| k != id);
    }

    /// A cover or image scaled to at most `width` pixels wide, decoded off the UI thread and cached. Returns the
    /// cached texture right away when there is one; otherwise calls `ready` once it has loaded.
    pub fn texture(
        self: &Rc<Self>,
        path: &Path,
        width: i32,
        ready: impl FnOnce(gdk::Texture) + 'static,
    ) -> Option<gdk::Texture> {
        let key = (path.to_path_buf(), width);
        if let Some(t) = self.textures.borrow().get(&key) {
            return Some(t.clone());
        }
        let app = self.clone();
        let p = path.to_path_buf();
        glib::spawn_future_local(async move {
            let decoded = gtk::gio::spawn_blocking(move || crate::media::decode_scaled(&p, width))
                .await
                .ok()
                .flatten();
            if let Some(img) = decoded {
                let t = img.texture();
                app.textures.borrow_mut().insert(key, t.clone());
                ready(t);
            }
        });
        None
    }

    pub fn forget_textures(&self, path: &Path) {
        self.textures.borrow_mut().retain(|(p, _), _| p != path);
    }

    /// The largest monitor scale (2 on HiDPI), so images are decoded no bigger than they're shown.
    pub fn ui_scale(&self) -> i32 {
        use gtk::gio::prelude::ListModelExt;
        let Some(d) = gdk::Display::default() else {
            return 1;
        };
        let mons = d.monitors();
        (0..mons.n_items())
            .filter_map(|i| mons.item(i).and_downcast::<gdk::Monitor>())
            .map(|m| m.scale_factor())
            .max()
            .unwrap_or(1)
            .max(1)
    }

    pub fn cover_path(&self, id: &str) -> Option<PathBuf> {
        let d = self.data.borrow();
        let b = d.book(id)?;
        b.has_cover.then(|| self.paths.cover(id))
    }

    pub fn window(&self) -> Option<gtk::ApplicationWindow> {
        self.window.borrow().clone()
    }

    pub fn open(&self, id: &str, at: Option<(i32, i32)>) {
        let f = self.open_book.borrow().clone();
        if let Some(f) = f {
            f(id, at);
        }
    }
}

/// Today's local date as a day number, and its "yyyy-MM-dd" key.
pub fn today() -> (i64, String) {
    let now = glib::DateTime::now_local().expect("local time");
    let day = gbook_core::dates::days_from_civil(
        now.year(),
        now.month() as u32,
        now.day_of_month() as u32,
    );
    (day, gbook_core::dates::key(day))
}
