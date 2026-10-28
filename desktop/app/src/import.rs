//! Adding books: the file picker, drag-and-drop and files opened from the file manager all end up here.
//! Parsing runs on a worker thread; the library is updated on the UI thread as each book finishes.

use std::path::PathBuf;

use gbook_core::library::{self, Imported};
use gtk::prelude::*;
use gtk::{gio, glib};

use crate::media::SystemMedia;
use crate::state::AppRc;

pub fn pick_files(app: &AppRc) {
    let filter = gtk::FileFilter::new();
    filter.set_name(Some("Books (EPUB, PDF)"));
    filter.add_mime_type("application/epub+zip");
    filter.add_mime_type("application/pdf");
    filter.add_suffix("epub");
    filter.add_suffix("pdf");
    let filters = gio::ListStore::new::<gtk::FileFilter>();
    filters.append(&filter);
    let dialog = gtk::FileDialog::builder()
        .title("Add Books")
        .filters(&filters)
        .default_filter(&filter)
        .modal(true)
        .build();
    let app = app.clone();
    let win = app.window();
    dialog.open_multiple(win.as_ref(), gio::Cancellable::NONE, move |res| {
        let Ok(list) = res else { return };
        let paths: Vec<PathBuf> = (0..list.n_items())
            .filter_map(|i| {
                list.item(i)
                    .and_downcast::<gio::File>()
                    .and_then(|f| f.path())
            })
            .collect();
        import_paths(&app, paths, false);
    });
}

/// Imports `paths` one after another, reporting progress in the busy toast. With `open_after`, a single book
/// opens in the reader once it's ready (for files opened from the file manager).
pub fn import_paths(app: &AppRc, paths: Vec<PathBuf>, open_after: bool) {
    let paths: Vec<PathBuf> = paths
        .into_iter()
        .filter(|p| {
            p.extension()
                .is_some_and(|e| e.eq_ignore_ascii_case("epub") || e.eq_ignore_ascii_case("pdf"))
                || library::is_pdf(p)
        })
        .collect();
    if paths.is_empty() {
        app.toast("Only EPUB and PDF books can be added");
        return;
    }
    let app = app.clone();
    glib::spawn_future_local(async move {
        let total = paths.len();
        let mut added = 0;
        let mut failed = 0;
        let mut last: Option<String> = None;
        for (i, p) in paths.into_iter().enumerate() {
            app.busy(Some(&if total == 1 {
                "Importing…".to_string()
            } else {
                format!("Importing {} of {total}…", i + 1)
            }));
            let known = app.data().books.clone();
            let lib = app.paths.clone();
            let res = gio::spawn_blocking(move || {
                let has = |id: &str| known.iter().find(|b| b.id == id).cloned();
                library::import_file(&lib, &has, &p, &SystemMedia)
            })
            .await;
            match res {
                Ok(Ok(Imported::Existing(b))) => {
                    last = Some(b.id);
                    added += 1;
                }
                Ok(Ok(Imported::New(b))) => {
                    last = Some(b.id.clone());
                    added += 1;
                    // Another import of the same file (or a sync) may have finished first.
                    app.update(|d| {
                        if d.book(&b.id).is_none() {
                            d.books.push(b);
                        }
                    });
                }
                Ok(Err(e)) => {
                    failed += 1;
                    eprintln!("gbook: import failed: {e}");
                    if total == 1 {
                        app.busy(None);
                        app.toast(&format!("Couldn't open that file: {e}"));
                        return;
                    }
                }
                Err(_) => failed += 1,
            }
        }
        app.busy(None);
        if total == 1 {
            if let Some(id) = &last {
                let title = app
                    .data()
                    .book(id)
                    .map(|b| b.title.clone())
                    .unwrap_or_default();
                app.toast(&format!("Added “{title}”"));
                if open_after {
                    app.update(|d| d.mark_opened(id));
                    app.open(id, None);
                }
            }
        } else if failed == 0 {
            app.toast(&format!("Added {added} books"));
        } else {
            app.toast(&format!(
                "Added {added} books · {failed} couldn't be opened"
            ));
        }
    });
}

/// Accepts books dropped onto `widget`.
pub fn drop_target(app: &AppRc, widget: &impl IsA<gtk::Widget>) {
    let target = gtk::DropTarget::new(
        gtk::gdk::FileList::static_type(),
        gtk::gdk::DragAction::COPY,
    );
    let app = app.clone();
    target.connect_drop(move |_, value, _, _| {
        let Ok(list) = value.get::<gtk::gdk::FileList>() else {
            return false;
        };
        let paths: Vec<PathBuf> = list.files().iter().filter_map(|f| f.path()).collect();
        import_paths(&app, paths, false);
        true
    });
    widget.add_controller(target);
}

/// Adds the free starter book that ships with GBook, once, as the Android app does.
pub fn add_starter(app: &AppRc) {
    if app.data().starter_added {
        return;
    }
    let file = "Fractured Minds.pdf";
    let candidates = [
        crate::assets_dir().join("starter").join(file),
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../gbook/app/src/main/assets/starter")
            .join(file),
    ];
    let Some(path) = candidates.into_iter().find(|p| p.is_file()) else {
        return;
    };
    let app = app.clone();
    glib::spawn_future_local(async move {
        let known = app.data().books.clone();
        let lib = app.paths.clone();
        let res = gio::spawn_blocking(move || {
            let has = |id: &str| known.iter().find(|b| b.id == id).cloned();
            library::import_file(&lib, &has, &path, &SystemMedia)
        })
        .await;
        app.update(|d| {
            if let Ok(Ok(Imported::New(mut b))) = res
                && d.book(&b.id).is_none()
            {
                // The PDF has no title and a short author name in its metadata.
                b.title = "Fractured Minds".into();
                b.author = "Galanda J. Sweya".into();
                d.books.push(b);
            }
            d.starter_added = true;
        });
    });
}
