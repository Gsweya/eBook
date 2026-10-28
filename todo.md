# GBook — To do

## Google Drive sync (Android and desktop)

Read on the phone, pick up in the same place on the PC. Everything stays in the user's own Drive, so GBook still
has no server of its own. Built: see `docs/sync.md` and the format in `shared/sync/README.md`.

### Done
- [x] **Storage:** sync document and book files in the Drive *appDataFolder* (`drive.appdata` scope only)
- [x] **What syncs:** reading position and progress, highlights and notes, reactions, posts (likes, saves),
  reading plans, collections, reminders, settings (not text size or margins), reading time and pages read
- [x] **Format:** one shared document, field-by-field stamps and tombstones, documented in `shared/sync/` with
  cases both test suites run (`cargo test -p gbook-core`, `./gradlew :app:testDebugUnitTest`)
- [x] **Merging:** newest reading position wins (`positionAt`); items merge by id and deletions spread;
  reading time is counted per device and summed; settings and other fields: last change wins, field by field
- [x] **When:** on start and every 5 minutes, after closing a book, on leaving the app (Android) or quitting
  (desktop); the document is only downloaded when its Drive version changed
- [x] **Android:** `AuthorizationClient` sign-in, Drive REST v3; local data stays the source of truth
- [x] **Desktop:** browser sign-in with PKCE on a loopback port, refresh token in the system keyring
- [x] **UI:** Settings › Sync (desktop), Settings › Google Drive & backup (Android): sign in, status, Sync now,
  Sync book files, sign out
- [x] **Testing:** two desktop copies through `scripts/fake-drive.py`: books, positions, reading time, settings
  and a deletion all crossed over

### To do
- [x] **Google Cloud:** project `ebook-513008`, Drive API, consent screen with `drive.appdata` + `email`,
  Android clients for the release and debug keys, desktop client; site and privacy policy on GitHub Pages,
  ownership verified in Search Console
- [ ] **Google Cloud, still to do:** Android clients for the Play app-signing key (and the two Android 17
  certificates), branding re-verification, publish to production; `GBOOK_GOOGLE_CLIENT_ID`/`_SECRET` repo secrets
- [x] **Desktop on a real account:** signed in and synced the library to Drive
- [x] **No blind overwrites:** first syncs only fill gaps, notes edited on both devices keep both texts,
  the last 10 libraries before a sync are kept in `sync/history/`
- [ ] "You read further on another device: jump there" in both readers
- [ ] **Testing on real devices:** the Pixel and the PC on one account, both editing offline then coming online;
  deleting on one side; two phones on one account
- [ ] Profile picture sync
- [ ] Show a sync status hint outside Settings (e.g. "Downloading 3 books…" on the Library)

## Android follow-ups
- [ ] Scanned PDFs with no text layer (e.g. *Computer Lib*): show the rendered page images in reading mode
- [ ] Soft fade at the ends of scrolling chapter names
- [ ] Desktop parity for the new reader bar (search, Aa, contents, ···), the goal ring with the Today sheet, and
  images in reflowed PDFs

## Desktop app (Rust): Linux and Windows

The Linux app is in `desktop/`: a GTK 4 UI (`app/`) over a UI-free core (`core/`) that shares the Android library
format. See the README's "Desktop app" section.

### Decisions
- [x] **UI toolkit (Linux):** GTK 4 with a custom stylesheet: smallest footprint on Linux (1.6 MB binary using the
  system GTK), Pango text with proper shaping, justification and native selection
- [x] **PDF rendering:** Poppler through `poppler-rs`, already installed with GNOME (no bundled PDFium); kept
  behind `app/src/media.rs` so it can be swapped
- [x] **EPUB:** own small parser (`core/src/epub.rs`, zip + forgiving XML), a port of `EpubParser.kt`
- [x] **Windows:** the same GTK 4 app, built in MSYS2 with GTK bundled; Inno Setup installer and portable zip
  (`desktop/packaging/windows/`, `docs/windows.md`)

### Core features (parity with Android)
- [x] Library: reading stats card, plans, Continue, collections, book grid with progress and reactions, Library
  views menu (All Books, Collections, Highlights, Plans); add books with the button, Ctrl+O, drag-and-drop or
  "Open with"
- [x] Book page: rating, reactions, Read, highlights, reading plan, collections, create posts, save a copy, remove
- [x] EPUB reader: chapters, contents, search, font size, line spacing, margins, bold, justify, page themes, scroll
  or page-at-a-time, page numbers and pages left in chapter
- [x] Same reading fonts and DM Sans for the UI (shared from `gbook/app/src/main/res/font`)
- [x] PDF page view with zoom (buttons, Ctrl+wheel, pinch)
- [ ] PDF reflowed text mode by outline (port `PdfLayout.kt` onto Poppler's text layout)
- [x] Highlights (marker and underline, five colours), notes, reactions, cross-paragraph selection
- [x] Feed of posts from highlights, key ideas and chapter recaps (port of `PostGenerator.kt`); likes, reactions,
  save, copy quote, save slide as image
- [x] Reading time tracking: today, 7-day chart, streak, totals, time per book, daily goal
- [x] Themes: accent palettes, light/dark/system (follows the desktop live), pure black; reader page themes
- [x] Settings: Profile, Reading, Goals, Appearance, Backup, About
- [x] Keyboard shortcuts: arrows/space/Page keys to turn pages, Ctrl +/- for text size, Ctrl+F search, [ ] for
  chapters, Ctrl+T contents, F11 full screen
- [x] Focus tracker: the wheel on the right edge (scroll, drag or click it; `.` and `,` keys), word or sentence
  steps, highlighting as it turns
- [x] Sidebar on wide windows, phone layout on narrow ones; rounded window with its
  own window buttons
- [ ] Reading reminders (desktop notifications)

### Data and sync
- [x] Same `library.json` schema as Android, unknown fields preserved on save
- [x] Import/export the Android backup `.zip` (library.json + books/)
- [x] Data in `~/.local/share/gbook` (`$GBOOK_DATA_DIR` overrides)
- [x] Optional Google Drive sync via the Drive API: see "Google Drive sync" at the top

### Performance (measured with 100 books, release build)
- [x] Cold start under 300 ms: ~250 ms to the first frame
- [x] EPUB parsing, cover decoding and PDF rendering off the UI thread; covers cached and decoded at display size
- [x] Memory under ~150 MB while reading a large PDF: ~145 MB, ~115 MB in an EPUB
- [ ] Profile scrolling on a 4K screen

### Packaging and release
- [x] `.deb` and `.rpm` via `desktop/packaging/build-packages.sh` (dpkg-deb, rpmbuild); needs GTK 4.14+, glibc 2.34+
- [x] Desktop entry, app icon, AppStream metadata, `.epub`/`.pdf` file associations
- [x] GitHub Actions (`.github/workflows/desktop.yml`): Linux and Windows packages, attached to `desktop-v*` releases
- [ ] Flatpak (would also cover older distributions)
- [x] Windows installer (`.exe`, Inno Setup) and portable `.zip`
- [ ] Windows code signing; `.msi` if needed; follow the Windows dark-mode setting

### Repo layout
- [ ] Move to a monorepo: `android/` (current `gbook/`), `desktop/`, `shared/` (JSON schema and sample data)
