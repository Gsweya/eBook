# GBook — To do

## Desktop app for Windows and Linux (Rust)

Build a native GBook desktop app in Rust for efficiency and performance, shipped as:

- **Windows:** `.msi` installer (and a portable `.exe`)
- **Linux:** `.deb` (Debian/Ubuntu) and `.rpm` (Fedora/openSUSE)

### Decisions to make first
- [ ] **UI toolkit** (native Rust, no web view, for low memory and fast startup):
  - **Slint** — declarative UI, GPU-accelerated, good for a polished themed look (leading option)
  - **iced** — Elm-style, pure Rust, good text rendering
  - **egui** — fastest to build, but looks less like the Android app
- [ ] **PDF rendering:** `pdfium-render` (PDFium, permissive licence) — avoid MuPDF bindings (AGPL)
- [ ] **EPUB:** `epub` crate or a small own parser (zip + XML), mirroring `EpubParser.kt`

### Core features (parity with Android)
- [ ] Library grid with covers, progress, reactions; add books (button + drag-and-drop onto the window)
- [ ] EPUB reader: reflowable text, chapters, contents, search, adjustable font size, line spacing, margins
- [ ] Bundle the same reading fonts (Literata, Charis SIL, Source Serif, Newsreader, Gelasio, Crimson Pro, EB Garamond, Inter, DM Sans) and DM Sans for the UI
- [ ] PDF page view with zoom, plus reflowed text mode by outline
- [ ] Highlights (marker and underline), notes, cross-paragraph selection
- [ ] Feed of posts generated from highlights and chapter key ideas (port `PostGenerator.kt`)
- [ ] Reading time tracking: today, 7-day chart, streak, totals, time per book
- [ ] Themes: accent palettes, light/dark/system, pure black; reader page themes
- [ ] Settings screens: Profile, Appearance, Navigation, Reading, Backup, About
- [ ] Keyboard shortcuts: arrows/space to scroll, Ctrl +/- for text size, Ctrl+F search, [ ] for chapters

### Data and sync
- [ ] Use the same `library.json` schema as Android (`LibraryData` in `Models.kt`) so data is interchangeable
- [ ] Import/export the Android backup `.zip` (library.json + books/), so a phone backup restores on desktop and vice versa
- [ ] Store data in the OS app-data folder (`%APPDATA%\GBook`, `~/.local/share/gbook`)
- [ ] Later: optional Google Drive sync via the Drive API (needs an OAuth client)

### Performance goals
- [ ] Cold start under 300 ms with a 100-book library
- [ ] Parse EPUBs and render PDF pages off the UI thread; cache covers and rendered pages
- [ ] Memory under ~150 MB while reading a large PDF
- [ ] Smooth 60+ fps scrolling and zooming

### Packaging and release
- [ ] `cargo-deb` for `.deb`, `cargo-generate-rpm` for `.rpm`, `cargo-wix` (or `cargo-packager`) for `.msi`
- [ ] Desktop entry, icon and `.epub`/`.pdf` file associations on both platforms
- [ ] GitHub Actions: build on Windows and Linux runners, attach `.msi`, `.exe`, `.deb`, `.rpm` to each GitHub release
- [ ] Code signing for the Windows installer (optional, avoids SmartScreen warnings)

### Repo layout
- [ ] Move to a monorepo: `android/` (current `gbook/`), `desktop/` (Rust workspace), `shared/` (JSON schema and sample data)
