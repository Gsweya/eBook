//! The window: Library, Feed, Settings and Search, reached from a sidebar on wide windows or the phone's
//! floating pill bar on narrow ones; the reader on top of them; toasts; and the window's
//! own rounded frame and buttons.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use gtk::prelude::*;
use gtk::{gdk, glib};

use crate::library::View;
use crate::state::AppRc;
use crate::widgets::{hbox, icon, vbox};
use crate::{feed, import, library, pdfview, reader, search, settings, sidebar};

/// Windows at least this wide get the sidebar; narrower ones the phone layout.
const WIDE: i32 = 900;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Tab {
    Library,
    Feed,
    Settings,
    Search,
}

impl Tab {
    fn name(self) -> &'static str {
        match self {
            Tab::Library => "library",
            Tab::Feed => "feed",
            Tab::Settings => "settings",
            Tab::Search => "search",
        }
    }
}

pub struct Shell {
    pub app: AppRc,
    main: gtk::Stack,
    tabs: gtk::Stack,
    nav: RefCell<Vec<(Tab, gtk::Button)>>,
    current: Cell<Tab>,
    pub settings_nav: RefCell<Option<Rc<dyn Fn(Option<&str>)>>>,
    pub search_focus: RefCell<Option<Rc<dyn Fn()>>>,
    /// Fills the Search tab's field.
    pub search_set: RefCell<Option<Rc<dyn Fn(&str)>>>,
    reader: RefCell<Option<gtk::Widget>>,
    /// Which part of the library the Library tab shows, and the collection when that's a collection.
    pub lib_view: Cell<View>,
    pub lib_collection: RefCell<String>,
    pub library_render: RefCell<Option<Rc<dyn Fn()>>>,
    pub sidebar_render: RefCell<Option<Rc<dyn Fn()>>>,
    pub wide: Cell<bool>,
    sidebar: RefCell<Option<gtk::Widget>>,
    nav_bar: RefCell<Option<gtk::Widget>>,
}

pub type ShellRc = Rc<Shell>;

impl Shell {
    pub fn select(&self, tab: Tab) {
        if tab == Tab::Settings
            && self.current.get() == Tab::Settings
            && let Some(f) = self.settings_nav.borrow().clone()
        {
            f(None);
        }
        self.current.set(tab);
        self.tabs.set_visible_child_name(tab.name());
        for (t, b) in self.nav.borrow().iter() {
            if *t == tab {
                b.add_css_class("active");
            } else {
                b.remove_css_class("active");
            }
        }
        if tab == Tab::Search
            && let Some(f) = self.search_focus.borrow().clone()
        {
            f();
        }
        self.refresh_sidebar();
    }

    pub fn current(&self) -> Tab {
        self.current.get()
    }

    fn refresh_sidebar(&self) {
        if let Some(f) = self.sidebar_render.borrow().clone() {
            f();
        }
    }

    /// Shows part of the library: the overview, all books, highlights, plans…
    pub fn show_library(&self, v: View) {
        self.lib_view.set(v);
        self.select(Tab::Library);
        if let Some(f) = self.library_render.borrow().clone() {
            f();
        }
    }

    pub fn show_collection(&self, id: &str) {
        *self.lib_collection.borrow_mut() = id.to_string();
        self.show_library(View::Collection);
    }

    /// Opens Search with `q` typed in.
    pub fn search_for(&self, q: &str) {
        if self.current.get() != Tab::Search {
            self.select(Tab::Search);
        }
        if let Some(f) = self.search_set.borrow().clone() {
            f(q);
        }
    }

    /// Switches between the sidebar (wide) and the phone layout (narrow).
    fn set_wide(&self, wide: bool) {
        if self.wide.replace(wide) == wide && self.sidebar.borrow().is_some() {
            return;
        }
        if let Some(s) = self.sidebar.borrow().as_ref() {
            s.set_visible(wide);
        }
        if let Some(n) = self.nav_bar.borrow().as_ref() {
            n.set_visible(!wide);
        }
        if let Some(w) = self.app.window() {
            if wide {
                w.add_css_class("wide");
                w.remove_css_class("narrow");
            } else {
                w.add_css_class("narrow");
                w.remove_css_class("wide");
            }
        }
        if let Some(f) = self.library_render.borrow().clone() {
            f();
        }
    }

    pub fn open_settings(&self, page: &str) {
        self.select(Tab::Settings);
        if let Some(f) = self.settings_nav.borrow().clone() {
            f(Some(page));
        }
    }

    /// Opens book `id` in the reader, at (chapter, block) when given.
    pub fn open_book(self: &Rc<Self>, id: &str, at: Option<(i32, i32)>) {
        let Some(book) = self.app.data().book(id).cloned() else {
            return;
        };
        self.close_reader();
        self.app.update(|d| d.mark_opened(id));
        let s = self.clone();
        let on_back: Rc<dyn Fn()> = Rc::new(move || s.close_reader());
        let w = match book.format {
            gbook_core::BookFormat::PDF => pdfview::open(&self.app, &book, on_back),
            gbook_core::BookFormat::EPUB => reader::open(&self.app, &book, at, on_back),
        };
        self.main.add_named(&w, Some("reader"));
        self.main.set_visible_child(&w);
        w.grab_focus();
        *self.reader.borrow_mut() = Some(w);
    }

    pub fn close_reader(&self) {
        if let Some(w) = self.reader.borrow_mut().take() {
            self.main.set_visible_child_name("home");
            let main = self.main.clone();
            // Let the cross-fade finish before dropping the reader.
            glib::timeout_add_local_once(Duration::from_millis(250), move || {
                main.remove(&w);
                drop(w);
                // The book's pages and textures are gone; give their memory back.
                glib::timeout_add_local_once(Duration::from_millis(200), crate::release_memory);
            });
            self.app.flush();
            crate::sync::soon(&self.app);
            if let Some(w) = self.app.window() {
                gtk::prelude::GtkWindowExt::set_focus(&w, None::<&gtk::Widget>);
            }
        }
    }

    pub fn in_reader(&self) -> bool {
        self.reader.borrow().is_some()
    }
}

pub fn build(gapp: &gtk::Application, app: &AppRc) {
    let window = gtk::ApplicationWindow::builder()
        .application(gapp)
        .title("GBook")
        .default_width(1180)
        .default_height(840)
        .build();
    window.add_css_class("gbook");
    window.add_css_class("narrow");
    // Clip the pages to the rounded frame.
    window.set_overflow(gtk::Overflow::Hidden);
    window.set_size_request(420, 560);
    // No system title bar: each screen's top bar is draggable and carries the window controls.
    let no_titlebar = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    no_titlebar.set_visible(false);
    window.set_titlebar(Some(&no_titlebar));
    *app.window.borrow_mut() = Some(window.clone());

    let main = gtk::Stack::new();
    main.set_transition_type(gtk::StackTransitionType::Crossfade);
    main.set_transition_duration(200);
    let tabs = gtk::Stack::new();
    tabs.set_transition_type(gtk::StackTransitionType::Crossfade);
    tabs.set_transition_duration(160);

    let shell = Rc::new(Shell {
        app: app.clone(),
        main: main.clone(),
        tabs: tabs.clone(),
        nav: RefCell::new(Vec::new()),
        current: Cell::new(Tab::Library),
        settings_nav: RefCell::new(None),
        search_focus: RefCell::new(None),
        search_set: RefCell::new(None),
        reader: RefCell::new(None),
        lib_view: Cell::new(View::Overview),
        lib_collection: RefCell::new(String::new()),
        library_render: RefCell::new(None),
        sidebar_render: RefCell::new(None),
        wide: Cell::new(false),
        sidebar: RefCell::new(None),
        nav_bar: RefCell::new(None),
    });
    let s = shell.clone();
    *app.open_book.borrow_mut() = Some(Rc::new(move |id, at| s.open_book(id, at)));

    tabs.add_named(&library::page(app, &shell), Some("library"));
    tabs.add_named(&feed::page(app, &shell), Some("feed"));
    tabs.add_named(&settings::page(app, &shell), Some("settings"));
    tabs.add_named(&search::page(app, &shell), Some("search"));

    let pages = gtk::Overlay::new();
    pages.set_child(Some(&tabs));
    pages.set_hexpand(true);
    let nav = nav_bar(&shell, &tabs);
    pages.add_overlay(&nav);
    *shell.nav_bar.borrow_mut() = Some(nav);
    // The accent glow runs behind the sidebar too, so its frosted glass has something to show.
    let home = hbox(0);
    home.add_css_class("backdrop-glow");
    let side = sidebar::build(app, &shell);
    home.append(&side);
    home.append(&pages);
    *shell.sidebar.borrow_mut() = Some(side);
    main.add_named(&home, Some("home"));

    let root = gtk::Overlay::new();
    root.set_child(Some(&main));
    root.add_overlay(&toast_host(app));
    root.add_overlay(&window_buttons(&window));
    // Follows the window's width to pick the sidebar or the phone layout.
    let sizer = gtk::DrawingArea::new();
    sizer.set_can_target(false);
    sizer.set_valign(gtk::Align::Start);
    sizer.set_content_height(1);
    root.add_overlay(&sizer);
    let s = shell.clone();
    sizer.connect_resize(move |_, w, _| s.set_wide(w >= WIDE));
    *app.sheet_host.borrow_mut() = Some(root.clone());
    window.set_child(Some(&root));
    import::drop_target(app, &window);
    shortcuts(&shell, &window);

    let a = app.clone();
    window.connect_close_request(move |w| {
        a.flush();
        // A last sync, with the window already gone from the screen.
        let win = w.clone();
        if crate::sync::finish_then(&a, move || win.destroy()) {
            w.set_visible(false);
            return glib::Propagation::Stop;
        }
        glib::Propagation::Proceed
    });
    let a = app.clone();
    window.connect_is_active_notify(move |w| {
        if w.is_active() {
            crate::sync::on_focus(&a);
        }
    });
    shell.select(Tab::Library);
    window.present();
    // Start with nothing focused, so the sidebar's search field doesn't light up.
    gtk::prelude::GtkWindowExt::set_focus(&window, None::<&gtk::Widget>);
    crate::devtools::run(&shell);
}

/// Close, minimise and maximise in the window's top-left corner: three soft circles of the same colour, told
/// apart by their symbols; close turns red and the others take the accent on hover.
fn window_buttons(window: &gtk::ApplicationWindow) -> gtk::Widget {
    let row = hbox(8);
    row.add_css_class("wbtns");
    row.set_halign(gtk::Align::Start);
    row.set_valign(gtk::Align::Start);
    let mut max_icon = None;
    for (class, ic, tip) in [
        ("close", "close", "Close"),
        ("min", "remove", "Minimise"),
        ("max", "crop-square", "Maximise"),
    ] {
        let b = gtk::Button::new();
        b.add_css_class("wbtn");
        b.add_css_class(class);
        let i = icon(ic, 13);
        b.set_child(Some(&i));
        b.set_tooltip_text(Some(tip));
        b.set_focusable(false);
        if class == "max" {
            max_icon = Some((i, b.clone()));
        }
        let w = window.downgrade();
        b.connect_clicked(move |_| {
            let Some(w) = w.upgrade() else { return };
            match class {
                "close" => w.close(),
                "min" => w.minimize(),
                _ => {
                    if w.is_maximized() {
                        w.unmaximize()
                    } else {
                        w.maximize()
                    }
                }
            }
        });
        row.append(&b);
    }
    // Maximise shows one square; restore, two.
    if let Some((i, b)) = max_icon {
        window.connect_maximized_notify(move |w| {
            let max = w.is_maximized();
            i.set_icon_name(Some(if max {
                "gbook-filter-none-symbolic"
            } else {
                "gbook-crop-square-symbolic"
            }));
            b.set_tooltip_text(Some(if max { "Restore" } else { "Maximise" }));
        });
    }
    row.upcast()
}

fn nav_bar(shell: &ShellRc, backdrop: &gtk::Stack) -> gtk::Widget {
    let row = hbox(10);
    row.add_css_class("navbar");
    row.set_halign(gtk::Align::Center);
    row.set_valign(gtk::Align::End);
    let pill = hbox(0);
    pill.add_css_class("navpill");
    let mut nav = Vec::new();
    for (tab, label, ic) in [
        (Tab::Library, "Library", "local-library"),
        (Tab::Feed, "Feed", "dynamic-feed"),
        (Tab::Settings, "Settings", "settings"),
    ] {
        let b = gtk::Button::new();
        let v = vbox(2);
        v.set_valign(gtk::Align::Center);
        v.append(&icon(ic, 24));
        v.append(&gtk::Label::new(Some(label)));
        b.set_child(Some(&v));
        b.set_tooltip_text(Some(label));
        let s = shell.clone();
        b.connect_clicked(move |_| s.select(tab));
        pill.append(&b);
        nav.push((tab, b));
    }
    // Frosted glass over the page scrolling beneath.
    let glass = crate::widgets::Frosted::new(backdrop, &pill);
    glass.add_css_class("navglass");
    row.append(&glass);
    let search = gtk::Button::new();
    search.add_css_class("navsearch");
    search.set_child(Some(&icon("search", 26)));
    search.set_tooltip_text(Some("Search (Ctrl+F)"));
    search.set_valign(gtk::Align::Center);
    let search_glass = crate::widgets::Frosted::new(backdrop, &search);
    search_glass.add_css_class("navglass");
    search_glass.add_css_class("navsearch-glass");
    search_glass.set_valign(gtk::Align::Center);
    let s = shell.clone();
    search.connect_clicked(move |_| s.select(Tab::Search));
    row.append(&search_glass);
    nav.push((Tab::Search, search));
    *shell.nav.borrow_mut() = nav;
    row.upcast()
}

/// Toasts and the busy indicator, sliding down from the top.
fn toast_host(app: &AppRc) -> gtk::Widget {
    let rev = gtk::Revealer::new();
    rev.set_transition_type(gtk::RevealerTransitionType::SlideDown);
    rev.set_halign(gtk::Align::Center);
    rev.set_valign(gtk::Align::Start);
    rev.set_can_target(false);
    let row = hbox(10);
    row.add_css_class("toast");
    let spinner = gtk::Spinner::new();
    let label = gtk::Label::new(None);
    label.set_wrap(true);
    label.set_max_width_chars(60);
    row.append(&spinner);
    row.append(&label);
    rev.set_child(Some(&row));
    let hide: Rc<RefCell<Option<glib::SourceId>>> = Rc::default();
    let r = rev.clone();
    *app.toast.borrow_mut() = Some(Rc::new(move |msg: &str, busy: bool| {
        if let Some(id) = hide.borrow_mut().take() {
            id.remove();
        }
        if msg.is_empty() {
            r.set_reveal_child(false);
            return;
        }
        label.set_text(msg);
        spinner.set_visible(busy);
        spinner.set_spinning(busy);
        r.set_reveal_child(true);
        if !busy {
            let r2 = r.clone();
            let h2 = hide.clone();
            *hide.borrow_mut() = Some(glib::timeout_add_local_once(
                Duration::from_millis(2400),
                move || {
                    h2.borrow_mut().take();
                    r2.set_reveal_child(false);
                },
            ));
        }
    }));
    rev.upcast()
}

fn shortcuts(shell: &ShellRc, window: &gtk::ApplicationWindow) {
    let keys = gtk::EventControllerKey::new();
    let s = shell.clone();
    keys.connect_key_pressed(move |_, key, _, mods| {
        let ctrl = mods.contains(gdk::ModifierType::CONTROL_MASK);
        if s.in_reader() || !ctrl {
            return glib::Propagation::Proceed;
        }
        match key {
            gdk::Key::o => import::pick_files(&s.app),
            gdk::Key::f | gdk::Key::k => s.select(Tab::Search),
            gdk::Key::_1 => s.select(Tab::Library),
            gdk::Key::_2 => s.select(Tab::Feed),
            gdk::Key::_3 | gdk::Key::comma => s.select(Tab::Settings),
            gdk::Key::q => {
                if let Some(w) = s.app.window() {
                    w.close();
                }
            }
            _ => return glib::Propagation::Proceed,
        }
        glib::Propagation::Stop
    });
    window.add_controller(keys);
}

/// A tab page: content in a scrolled window that runs under the top bar and the floating nav bar, re-rendered
/// whenever the library changes while it's on screen (or when it next comes on screen).
pub fn scrolled_page(
    app: &AppRc,
    top: &gtk::WindowHandle,
    content: &gtk::Widget,
    render: Rc<dyn Fn()>,
) -> gtk::Widget {
    let scroller = gtk::ScrolledWindow::new();
    scroller.set_hscrollbar_policy(gtk::PolicyType::Never);
    scroller.set_child(Some(content));
    let overlay = gtk::Overlay::new();
    overlay.set_child(Some(&scroller));
    overlay.add_overlay(top);
    let dirty = Rc::new(Cell::new(true));
    let d = dirty.clone();
    let r = render.clone();
    let o = overlay.downgrade();
    app.on_change(move || {
        if o.upgrade().is_some_and(|o| o.is_mapped()) {
            r();
            d.set(false);
        } else {
            d.set(true);
        }
    });
    overlay.connect_map(move |_| {
        if dirty.replace(false) {
            render();
        }
    });
    overlay.upcast()
}
