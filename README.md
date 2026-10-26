# GBook

An Android eReader in Kotlin + Jetpack Compose for EPUB and PDF, with Instagram-style posts generated from your books and highlights.

## Features

- **Library** of EPUB and PDF books with covers, progress and reactions; add books with the **+** in the bottom bar.
- **Reader** with nine open-licence reading fonts, text size buttons and pinch-to-resize, themes, line spacing and margins, contents and search.
- **Highlights** across paragraphs with drag handles, a lens loupe, and a marker or underline style.
- **Feed** of posts built on-device from chapter key ideas, recap carousels and your highlights.
- **PDF** page view with zoom, plus a reflowed text mode split by the PDF outline.
- **Themes**: accent palettes, light/dark/system mode and pure black.
- **Google Drive** backup, restore and import through the system file picker.

## Build

Requires JDK 21 and the Android SDK (compileSdk 36).

```sh
cd gbook
./gradlew installDebug
```

To bundle sample books, put `.epub`/`.pdf` files in `sample_books/` (git-ignored); they are imported on first launch.

## Project layout

- `gbook/` — the Android app (`com.gbook`)
- `docs/spec.md`, `docs/spec-notes/` — design spec notes
- `bookgram-monorepo-structure.md` — original idea document

Fonts in `gbook/app/src/main/res/font` are licensed under the SIL Open Font License 1.1.
