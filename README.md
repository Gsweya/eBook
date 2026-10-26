# GBook

An open-source Android eReader for EPUB and PDF, built with Kotlin and Jetpack Compose. It turns your books and highlights into Instagram-style posts you can scroll through to remember what you read.

**[Download the latest APK](https://github.com/Gsweya/eBook/releases/latest)** — open it on your phone and allow installing from your browser or file manager.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/10-library.png" width="200" alt="Library and reading stats"><br><sub>Library and reading stats</sub></td>
    <td align="center"><img src="docs/screenshots/11-library-menu.png" width="200" alt="Library views"><br><sub>Library views</sub></td>
    <td align="center"><img src="docs/screenshots/15-book-detail.png" width="200" alt="Book page and rating"><br><sub>Book page and rating</sub></td>
    <td align="center"><img src="docs/screenshots/16-book-options.png" width="200" alt="Book options"><br><sub>Book options</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/50-reader.png" width="200" alt="Reader with highlights"><br><sub>Reader with highlights</sub></td>
    <td align="center"><img src="docs/screenshots/52-reader-options.png" width="200" alt="Reading options with live preview"><br><sub>Reading options with live preview</sub></td>
    <td align="center"><img src="docs/screenshots/60-pages.png" width="200" alt="Page-turn mode"><br><sub>Page-turn mode</sub></td>
    <td align="center"><img src="docs/screenshots/54-reader-fonts.png" width="200" alt="Ten reading fonts"><br><sub>Ten reading fonts</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/61-tracker.png" width="200" alt="Reading tracker"><br><sub>Reading tracker</sub></td>
    <td align="center"><img src="docs/screenshots/63-wheel-highlight.png" width="200" alt="Highlight with the wheel"><br><sub>Highlight with the wheel</sub></td>
    <td align="center"><img src="docs/screenshots/62-tracker-options.png" width="200" alt="Tracker options"><br><sub>Tracker options</sub></td>
    <td align="center"><img src="docs/screenshots/59-highlight-actions.png" width="200" alt="Colours and reactions"><br><sub>Colours and reactions</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/22-highlights.png" width="200" alt="Highlights by book"><br><sub>Highlights by book</sub></td>
    <td align="center"><img src="docs/screenshots/23-book-highlights.png" width="200" alt="A book's highlights"><br><sub>A book's highlights</sub></td>
    <td align="center"><img src="docs/screenshots/20-feed.png" width="200" alt="Feed of posts"><br><sub>Feed of posts</sub></td>
    <td align="center"><img src="docs/screenshots/14-collection.png" width="200" alt="Collections"><br><sub>Collections</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/30-search.png" width="200" alt="Search"><br><sub>Search</sub></td>
    <td align="center"><img src="docs/screenshots/40-settings.png" width="200" alt="Settings"><br><sub>Settings</sub></td>
    <td align="center"><img src="docs/screenshots/70-dark-library.png" width="200" alt="Dark mode"><br><sub>Dark mode</sub></td>
    <td align="center"><img src="docs/screenshots/74-dark-reader.png" width="200" alt="Dark reader"><br><sub>Dark reader</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/01-splash.png" width="200" alt="Welcome"><br><sub>Welcome</sub></td>
    <td align="center"><img src="docs/screenshots/03-crop.png" width="200" alt="Profile photo cropper"><br><sub>Profile photo cropper</sub></td>
  </tr>
</table>

## Features

- **Starts with a free book**: *Fractured Minds* by Galanda J. Sweya ships with the app, so there's something to read right away.
- **Library** of EPUB and PDF books with covers, progress and reactions; add several books at once with the **+** button. A menu under the title switches between the overview, all books, collections, highlights and reading plans.
- **Book page**: a full-screen sheet over the blurred cover with a star rating, reactions, and cards for highlights, reading plan, collections, posts, Drive export and removal.
- **Collections**: select books and group them into named collections.
- **Search** tab for books, authors, collections and highlights.
- **First-run welcome**: a short splash, then set up your profile picture (with a circle cropper) and import your first books.
- **Reading time**: today's minutes, a 7-day chart, streak and all-time totals, plus time spent in each book.
- **Reader** with ten open-licence reading fonts (including Libre Baskerville), text size buttons and pinch-to-resize, page themes, line spacing, adjustable margins, contents and search, all in one **Reading options** sheet with a live preview of the page you're on.
- **Scroll or turn pages**: scroll through a whole chapter, or read one page at a time and tap or swipe to turn. Both show the page number, pages left in the chapter and a progress line.
- **Reading tracker**: turn the wheel on the right edge to move a marker through the text word by word or sentence by sentence. Pick a highlight colour in its options and turning the wheel highlights what the marker passes. The wheel can sit in the middle or at the bottom of the screen.
- **Highlights** across paragraphs with drag handles, a lens loupe, and a marker or underline style, grouped by book in the Library and searchable by book or wording.
- **Feed** of posts built on-device from chapter key ideas, recap carousels and your highlights.
- **PDF** page view with zoom, plus a reflowed text mode that reads like an EPUB: paragraphs, headings and named chapters are rebuilt from the page layout, and running headers and page numbers are dropped.
- **Make it yours**: accent palettes, light/dark/system mode, pure black, two bottom-bar styles with a separate search button, symbol or Noto 3D emoji icons, and a profile picture.
- **Google Drive** backup, restore and import through the system file picker.

## Build

Requires JDK 21 and the Android SDK (compileSdk 36).

```sh
cd gbook
./gradlew installDebug        # debug build on a connected phone
./gradlew assembleRelease     # release APK in app/build/outputs/apk/release/
```

Release signing uses a `gbook/keystore.properties` file (not committed) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it the release APK is built unsigned.

Debug builds also bundle any `.epub`/`.pdf` files you put in `sample_books/` (git-ignored); they are imported on first launch.

## Change how it looks

Everything visual lives in a few files under `gbook/app/src/main/java/com/gbook/ui/`:

| What | Where |
|---|---|
| Accent palettes, light/dark colours | `theme/Theme.kt` (`AccentPalettes`, `gColors`) |
| Reading fonts and the app font | `theme/Fonts.kt`, font files in `res/font/` |
| Buttons and surfaces | `components/Surfaces.kt` |
| Bottom bars (pill and classic) | `components/NavBar.kt` |
| Reader page themes | `theme/Theme.kt` (`ReaderThemes`) |
| Post card styles | `screens/FeedScreen.kt` |

Add a palette to `AccentPalettes` and it appears in **Settings → Appearance** automatically.

## Project layout

- `gbook/` — the Android app (`com.gbook`)
- `docs/spec.md`, `docs/spec-notes/` — design notes
- `bookgram-monorepo-structure.md` — original idea document

## Licences

GBook is released under the [MIT License](LICENSE). Bundled fonts are under the SIL Open Font License 1.1; the 3D icons are [Noto 3D Emoji](https://github.com/googlefonts/noto-emoji) under the Apache License 2.0.
