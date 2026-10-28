//! Google Drive sync for the running app: when to sync, and the steps of one sync. Network calls run on
//! worker threads; the merge (`gbook_core::sync`, rules in `shared/sync/README.md`) runs on the UI thread, so
//! it sees the library exactly as it is and nothing typed meanwhile is lost.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use gbook_core::LibraryData;
use gbook_core::library::{self, Imported};
use gbook_core::model::now_ms;
use gbook_core::store::{self, Paths};
use gbook_core::sync as merge;
use gtk::{gio, glib};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::drive::{self, Drive, DriveFile, Error};
use crate::media::SystemMedia;
use crate::state::AppRc;

/// How often to sync while the app is open.
const EVERY: Duration = Duration::from_secs(300);
const DOC_MIME: &str = "application/gzip";

/// Saved in `sync/state.json`.
#[derive(Serialize, Deserialize, Clone)]
#[serde(default, rename_all = "camelCase")]
pub struct State {
    /// This device's name in the sync document.
    pub device: String,
    pub enabled: bool,
    /// Upload and download book files too, not just reading state.
    pub sync_books: bool,
    pub account: String,
    pub last_sync: i64,
    /// Drive version of the sync document when this device last read or wrote it.
    pub version: i64,
    /// Hash of the document as it is on Drive, so an unchanged one isn't uploaded again.
    pub drive_hash: String,
    /// Why the last sync failed, if it did.
    pub error: String,
}

impl Default for State {
    fn default() -> Self {
        State {
            device: String::new(),
            enabled: false,
            sync_books: true,
            account: String::new(),
            last_sync: 0,
            version: 0,
            drive_hash: String::new(),
            error: String::new(),
        }
    }
}

pub struct Sync {
    dir: PathBuf,
    state: RefCell<State>,
    running: Cell<bool>,
    again: Cell<bool>,
    /// What a running sync is doing, e.g. "Downloading books (2 of 5)…".
    progress: RefCell<String>,
    listeners: RefCell<Vec<Box<dyn Fn() -> bool>>>,
    on_done: RefCell<Vec<Box<dyn FnOnce()>>>,
    soon: RefCell<Option<glib::SourceId>>,
    /// Set while the browser sign-in is open; storing true cancels it.
    signing_in: RefCell<Option<Arc<AtomicBool>>>,
}

impl Sync {
    pub fn new(paths: &Paths) -> Sync {
        let dir = paths.sync_dir();
        let mut state: State = fs::read(dir.join("state.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        let fresh = state.device.is_empty();
        if fresh {
            state.device = merge::new_device_id();
        }
        let s = Sync {
            dir,
            state: RefCell::new(state),
            running: Cell::new(false),
            again: Cell::new(false),
            progress: RefCell::new(String::new()),
            listeners: RefCell::new(Vec::new()),
            on_done: RefCell::new(Vec::new()),
            soon: RefCell::new(None),
            signing_in: RefCell::new(None),
        };
        if fresh {
            s.save_state();
        }
        s
    }

    pub fn state(&self) -> State {
        self.state.borrow().clone()
    }

    pub fn running(&self) -> bool {
        self.running.get()
    }

    pub fn signing_in(&self) -> bool {
        self.signing_in.borrow().is_some()
    }

    fn edit(&self, f: impl FnOnce(&mut State)) {
        f(&mut self.state.borrow_mut());
        self.save_state();
    }

    fn save_state(&self) {
        if let Ok(b) = serde_json::to_vec_pretty(&*self.state.borrow()) {
            let _ = store::write_atomic(&self.dir.join("state.json"), &b);
        }
    }

    /// Calls `f` whenever the sync status changes, for as long as it returns true.
    pub fn on_change(&self, f: impl Fn() -> bool + 'static) {
        self.listeners.borrow_mut().push(Box::new(f));
    }

    fn changed(&self) {
        let ls = self.listeners.take();
        let mut keep: Vec<_> = ls.into_iter().filter(|f| f()).collect();
        let mut cur = self.listeners.borrow_mut();
        keep.append(&mut cur);
        *cur = keep;
    }

    /// One line for the settings page: "Synced 2 min ago", "Syncing…", or what went wrong.
    pub fn status(&self) -> String {
        if self.running.get() {
            let p = self.progress.borrow();
            return if p.is_empty() {
                "Syncing…".into()
            } else {
                p.clone()
            };
        }
        let s = self.state.borrow();
        if !s.error.is_empty() {
            return format!("Couldn't sync: {}", s.error);
        }
        if s.last_sync == 0 {
            return "Not synced yet".into();
        }
        let mins = (now_ms() - s.last_sync) / 60_000;
        if mins < 1 {
            "Synced just now".into()
        } else if mins < 60 {
            format!("Synced {mins} min ago")
        } else if mins < 24 * 60 {
            format!("Synced {} h ago", mins / 60)
        } else {
            glib::DateTime::from_unix_local(s.last_sync / 1000)
                .ok()
                .and_then(|d| d.format("Synced %-d %b, %H:%M").ok())
                .map(|s| s.to_string())
                .unwrap_or_default()
        }
    }

    fn doc_path(&self) -> PathBuf {
        self.dir.join("doc.json")
    }

    fn base_path(&self) -> PathBuf {
        self.dir.join("base.json")
    }

    fn load_doc(&self) -> Value {
        read_json(&self.doc_path()).unwrap_or_else(merge::empty_doc)
    }

    /// Forgets this device's sync history, so the next sync is a first sync.
    fn reset(&self) {
        let _ = fs::remove_file(self.doc_path());
        let _ = fs::remove_file(self.base_path());
        self.edit(|s| {
            s.version = 0;
            s.drive_hash.clear();
            s.last_sync = 0;
        });
    }
}

fn read_json(path: &std::path::Path) -> Option<Value> {
    serde_json::from_slice(&fs::read(path).ok()?).ok()
}

fn write_json(path: &std::path::Path, v: &Value) {
    if let Err(e) = serde_json::to_vec(v)
        .map_err(std::io::Error::other)
        .and_then(|b| store::write_atomic(path, &b))
    {
        eprintln!("gbook: couldn't save sync state: {e}");
    }
}

fn hash(v: &Value) -> String {
    // serde_json maps keep their keys sorted, so equal documents serialise the same.
    let d = Sha256::digest(serde_json::to_vec(v).unwrap_or_default());
    d.iter().map(|b| format!("{b:02x}")).collect()
}

fn notify(app: &AppRc) {
    let a = app.clone();
    glib::idle_add_local_once(move || a.sync.changed());
}

/// Syncs now if sync is on, then every few minutes while the app runs.
pub fn start(app: &AppRc) {
    sync_now(app);
    let a = app.clone();
    glib::timeout_add_local(EVERY, move || {
        sync_now(&a);
        glib::ControlFlow::Continue
    });
}

/// Syncs a moment from now, once however many times it's asked: after closing a book, say.
pub fn soon(app: &AppRc) {
    if !app.sync.state.borrow().enabled {
        return;
    }
    if let Some(id) = app.sync.soon.borrow_mut().take() {
        id.remove();
    }
    let a = app.clone();
    let id = glib::timeout_add_local_once(Duration::from_secs(3), move || {
        a.sync.soon.borrow_mut().take();
        sync_now(&a);
    });
    *app.sync.soon.borrow_mut() = Some(id);
}

/// Syncs when the window comes back to the front after a while away.
pub fn on_focus(app: &AppRc) {
    let s = app.sync.state.borrow().clone();
    if s.enabled && !app.sync.running.get() && now_ms() - s.last_sync > 60_000 {
        sync_now(app);
    }
}

pub fn sync_now(app: &AppRc) {
    let s = &app.sync;
    if !s.state.borrow().enabled {
        return;
    }
    if s.running.replace(true) {
        s.again.set(true);
        return;
    }
    s.progress.borrow_mut().clear();
    notify(app);
    let app = app.clone();
    glib::spawn_future_local(async move {
        let r = run(&app).await;
        let s = &app.sync;
        s.running.set(false);
        s.progress.borrow_mut().clear();
        match r {
            Ok(()) => s.edit(|st| {
                st.last_sync = now_ms();
                st.error.clear();
            }),
            Err(e @ (Error::SignedOut | Error::NoDriveAccess)) => s.edit(|st| {
                st.enabled = false;
                st.error = e.to_string();
            }),
            Err(Error::Failed(e)) => {
                eprintln!("gbook: sync failed: {e}");
                s.edit(|st| st.error = e);
            }
        }
        notify(&app);
        let done: Vec<_> = s.on_done.take();
        for f in done {
            f();
        }
        if s.again.replace(false) {
            sync_now(&app);
        }
    });
}

/// Runs a last sync before the app closes and calls `done` once it has finished (or after a while, whatever
/// happens). Returns false, without calling `done`, when sync is off.
pub fn finish_then(app: &AppRc, done: impl FnOnce() + 'static) -> bool {
    if !app.sync.state.borrow().enabled {
        return false;
    }
    let once: Rc<RefCell<Option<Box<dyn FnOnce()>>>> = Rc::new(RefCell::new(Some(Box::new(done))));
    let o = once.clone();
    app.sync.on_done.borrow_mut().push(Box::new(move || {
        if let Some(f) = o.borrow_mut().take() {
            f();
        }
    }));
    glib::timeout_add_local_once(Duration::from_secs(20), move || {
        if let Some(f) = once.borrow_mut().take() {
            f();
        }
    });
    if app.sync.running.get() {
        app.sync.again.set(false);
    } else {
        sync_now(app);
    }
    true
}

pub fn sign_in(app: &AppRc) {
    let pending = match drive::begin_sign_in() {
        Ok(p) => p,
        Err(e) => {
            app.toast(&format!("Couldn't start signing in: {e}"));
            return;
        }
    };
    *app.sync.signing_in.borrow_mut() = Some(pending.cancel.clone());
    crate::widgets::open_uri(app, &pending.url);
    notify(app);
    let app = app.clone();
    let dir = app.sync.dir.clone();
    glib::spawn_future_local(async move {
        let r = gio::spawn_blocking(move || {
            let acct = pending.finish()?;
            drive::save_refresh_token(&dir, &acct.refresh_token)?;
            Ok::<_, Error>(acct.email)
        })
        .await
        .unwrap_or_else(|_| Err(Error::Failed("Sign-in failed".into())));
        let s = &app.sync;
        let cancelled = s
            .signing_in
            .borrow_mut()
            .take()
            .is_some_and(|c| c.load(Ordering::Relaxed));
        match r {
            Ok(email) => {
                if s.state.borrow().account != email {
                    s.reset();
                }
                s.edit(|st| {
                    st.enabled = true;
                    st.account = email;
                    st.error.clear();
                });
                sync_now(&app);
            }
            Err(_) if cancelled => {}
            Err(e) => app.toast(&e.to_string()),
        }
        notify(&app);
    });
}

pub fn cancel_sign_in(app: &AppRc) {
    if let Some(c) = app.sync.signing_in.borrow().as_ref() {
        c.store(true, Ordering::Relaxed);
    }
}

/// Stops syncing and forgets the account; the library stays as it is on this device.
pub fn sign_out(app: &AppRc) {
    let s = &app.sync;
    s.edit(|st| {
        st.enabled = false;
        st.account.clear();
        st.error.clear();
    });
    s.reset();
    let dir = s.dir.clone();
    std::thread::spawn(move || drive::sign_out(&dir));
    notify(app);
}

pub fn set_sync_books(app: &AppRc, on: bool) {
    app.sync.edit(|s| s.sync_books = on);
    if on {
        sync_now(app);
    }
}

/// Blocking work on a worker thread.
async fn worker<T: Send + 'static>(
    f: impl FnOnce() -> Result<T, Error> + Send + 'static,
) -> Result<T, Error> {
    gio::spawn_blocking(f)
        .await
        .unwrap_or_else(|_| Err(Error::Failed("Sync stopped unexpectedly".into())))
}

fn set_progress(app: &AppRc, p: String) {
    *app.sync.progress.borrow_mut() = p;
    notify(app);
}

/// The library as JSON, as the merge sees it.
fn library_json(app: &AppRc) -> Value {
    serde_json::to_value(&*app.data()).unwrap_or_default()
}

/// Merges on the UI thread and applies the result; returns the new base.
fn merge_and_apply(app: &AppRc, doc: &mut Value, remote: Option<&Value>) -> Result<Value, Error> {
    let s = &app.sync;
    let (device, base) = (s.state.borrow().device.clone(), read_json(&s.base_path()));
    let local = library_json(app);
    let projected = merge::merge(doc, remote, base.as_ref(), &local, &device, now_ms());
    if projected != local {
        let data: LibraryData = serde_json::from_value(projected)
            .map_err(|e| Error::Failed(format!("Unreadable library from sync: {e}")))?;
        save_snapshot(&s.dir, &local);
        let keep: HashSet<String> = data.books.iter().map(|b| b.id.clone()).collect();
        let gone: Vec<String> = app
            .data()
            .books
            .iter()
            .filter(|b| !keep.contains(&b.id))
            .map(|b| b.id.clone())
            .collect();
        app.update(|d| *d = data);
        for id in gone {
            app.forget_content(&id);
            store::delete_book_files(&app.paths, &id);
        }
    }
    let base = library_json(app);
    write_json(&s.doc_path(), doc);
    write_json(&s.base_path(), &base);
    Ok(base)
}

/// How many earlier versions of the library to keep in `sync/history`.
const SNAPSHOTS: usize = 10;

/// Keeps the library as it was before a sync changed it, so nothing a sync replaced is gone for good. Restore
/// one by copying it over `library.json` while GBook is closed.
fn save_snapshot(dir: &std::path::Path, library: &Value) {
    let history = dir.join("history");
    write_json(&history.join(format!("library-{}.json", now_ms())), library);
    let mut old: Vec<PathBuf> = fs::read_dir(&history)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
        .collect();
    old.sort();
    for p in old.iter().rev().skip(SNAPSHOTS) {
        let _ = fs::remove_file(p);
    }
}

fn mime(name: &str) -> &'static str {
    if name.ends_with(".pdf") {
        "application/pdf"
    } else {
        "application/epub+zip"
    }
}

async fn run(app: &AppRc) -> Result<(), Error> {
    let s = &app.sync;
    let dir = s.dir.clone();
    let st = s.state();

    // 1. Pull, when the document on Drive changed since this device last saw it.
    let (drive, files, remote, primary, extras) = worker(move || {
        let drive = Drive::connect(&dir)?;
        let files = drive.list()?;
        let mut docs: Vec<DriveFile> = files
            .iter()
            .filter(|f| f.name == merge::DOC_NAME)
            .cloned()
            .collect();
        docs.sort_by(|a, b| a.id.cmp(&b.id));
        let primary = docs.first().cloned();
        let extras: Vec<DriveFile> = docs.iter().skip(1).cloned().collect();
        let mut remote: Option<Value> = None;
        if docs.len() > 1 || primary.as_ref().is_some_and(|p| p.version != st.version) {
            // Two devices that both started syncing at once each made a document: join them all.
            let mut joined = merge::empty_doc();
            for d in &docs {
                merge::join(
                    &mut joined,
                    &merge::decode(&drive.download(&d.id)?).map_err(Error::Failed)?,
                );
            }
            remote = Some(joined);
        }
        Ok((drive, files, remote, primary, extras))
    })
    .await?;

    // 2–3. Record and project.
    let mut doc = s.load_doc();
    let base = merge_and_apply(app, &mut doc, remote.as_ref())?;

    // 4. Books that are new to this device.
    let sync_books = s.state.borrow().sync_books;
    let by_name: HashMap<&str, &DriveFile> = files.iter().map(|f| (f.name.as_str(), f)).collect();
    if sync_books {
        let todo: Vec<(String, DriveFile)> = merge::pending_books(&doc, &base)
            .into_iter()
            .filter_map(|(id, name)| {
                let f = by_name.get(merge::book_file_name(&id, &name).as_str())?;
                Some((id, (*f).clone()))
            })
            .collect();
        let mut imported = 0;
        for (i, (id, f)) in todo.iter().enumerate() {
            set_progress(
                app,
                format!("Downloading books ({} of {})…", i + 1, todo.len()),
            );
            let known = app.data().books.clone();
            let (paths, d, f) = (app.paths.clone(), drive.clone(), f.clone());
            let r = worker(move || {
                let tmp = paths.cache_dir().join(format!("sync-{}", f.name));
                d.download_to(&f.id, &tmp)?;
                let has = |id: &str| known.iter().find(|b| b.id == id).cloned();
                let r = library::import_file(&paths, &has, &tmp, &SystemMedia);
                let _ = fs::remove_file(&tmp);
                r.map_err(Error::Failed)
            })
            .await;
            match r {
                Ok(Imported::New(b)) if b.id == *id => {
                    // The starter book, say, may have been added meanwhile.
                    app.update(|d| {
                        if d.book(&b.id).is_none() {
                            d.books.push(b);
                        }
                    });
                    imported += 1;
                }
                Ok(Imported::New(b)) => store::delete_book_files(&app.paths, &b.id),
                Ok(Imported::Existing(_)) => {}
                Err(e) => eprintln!("gbook: couldn't download book {id}: {e}"),
            }
        }
        if imported > 0 {
            merge_and_apply(app, &mut doc, None)?;
        }
    }

    // 5. Push the document if Drive's copy is behind.
    let now_hash = hash(&doc);
    let drive_hash = match &remote {
        Some(r) => hash(r),
        None => s.state.borrow().drive_hash.clone(),
    };
    if now_hash != drive_hash || primary.is_none() || !extras.is_empty() {
        set_progress(app, "Syncing…".into());
        let bytes = merge::encode(&doc);
        let (d, p) = (drive.clone(), primary.clone());
        let saved = worker(move || {
            let f = match &p {
                Some(p) => d.update(&p.id, &bytes, DOC_MIME)?,
                None => d.create(merge::DOC_NAME, &bytes, DOC_MIME)?,
            };
            for x in &extras {
                d.delete(&x.id)?;
            }
            Ok(f)
        })
        .await?;
        s.edit(|st| {
            st.version = saved.version;
            st.drive_hash = now_hash;
        });
    } else if let Some(p) = &primary {
        s.edit(|st| {
            st.version = p.version;
            st.drive_hash = now_hash;
        });
    }

    // 6. Upload books Drive doesn't have; delete the files of deleted books and duplicate uploads.
    if sync_books && s.state.borrow().enabled {
        let uploads: Vec<(String, PathBuf)> = app
            .data()
            .books
            .iter()
            .map(|b| {
                (
                    merge::book_file_name(&b.id, &b.file_name),
                    app.paths.book_file(b),
                )
            })
            .filter(|(name, path)| !by_name.contains_key(name.as_str()) && path.is_file())
            .collect();
        let n = uploads.len();
        for (i, (name, path)) in uploads.into_iter().enumerate() {
            set_progress(app, format!("Uploading books ({} of {n})…", i + 1));
            let d = drive.clone();
            worker(move || d.upload_file(&name, &path, mime(&name)).map(|_| ())).await?;
        }
        let dead = merge::deleted_books(&doc);
        let mut seen: HashSet<&str> = HashSet::new();
        let mut sorted: Vec<&DriveFile> = files.iter().collect();
        sorted.sort_by(|a, b| a.id.cmp(&b.id));
        let removals: Vec<String> = sorted
            .into_iter()
            .filter(|f| match merge::book_id_of_file(&f.name) {
                Some(id) => dead.contains(id) || !seen.insert(f.name.as_str()),
                None => false,
            })
            .map(|f| f.id.clone())
            .collect();
        if !removals.is_empty() {
            let d = drive.clone();
            worker(move || {
                for id in &removals {
                    d.delete(id)?;
                }
                Ok(())
            })
            .await?;
        }
    }
    Ok(())
}
