# Bookgram — Monorepo Structure & Screen Layouts

## 1. Repo Layout

```
bookgram/
├── README.md                     # what this is, how to build, module map
├── docs/
│   ├── spec.md                   # bookgram-spec.md (the one already written)
│   ├── screens.md                # this file's §2, kept in sync with Figma/wireframes
│   └── db-schema.sql             # source of truth for the Room schema
│
├── app/                          # Android app module (Kotlin/Compose)
│   ├── build.gradle.kts
│   └── src/main/java/com/bookgram/
│       ├── MainActivity.kt
│       ├── di/                   # Hilt/Koin modules
│       ├── data/
│       │   ├── db/               # Room: entities, DAOs, AppDatabase
│       │   ├── epub/             # EPUB parsing
│       │   └── repository/       # BookRepository, HighlightRepository, FeedRepository
│       ├── domain/
│       │   ├── model/            # plain Kotlin data classes (Book, Highlight, Post...)
│       │   └── usecase/          # GeneratePostsFromBook, ExportHighlights, etc.
│       ├── ui/
│       │   ├── theme/            # glass.kt (blur/translucency tokens), Color.kt, Type.kt
│       │   ├── library/          # LibraryScreen.kt + LibraryViewModel.kt
│       │   ├── reader/           # ReaderScreen.kt + ReaderViewModel.kt
│       │   ├── feed/             # FeedScreen.kt, FeedBuilderScreen.kt
│       │   └── components/       # GlassCard, GlassToolbar, PostCard, BookCover
│       └── navigation/           # NavGraph.kt
│   └── src/test/                 # unit tests (repositories, use cases)
│
├── landing/                      # React landing page (download .apk / Play Store)
│   ├── package.json
│   ├── index.html
│   └── src/
│       ├── App.jsx                # bookgram-landing.jsx lives here
│       └── styles/
│
├── scripts/
│   ├── build-apk.sh
│   └── export-schema.sh          # dumps Room schema -> docs/db-schema.sql
│
└── .github/workflows/            # optional CI: lint + assemble debug APK
    └── android-build.yml
```

**Separation principle:** `data/` never imports from `ui/`; `ui/` never touches Room directly, only `domain` use cases. `landing/` is fully independent — no shared code with `app/`, since it's just a static download page.

## 2. Module Documentation (one README per top-level folder)

| Folder | README covers |
|---|---|
| `app/` | how to build/run, min SDK, Compose version, glass-effect tokens |
| `app/data/` | Room DB versioning, EPUB parser notes, migration policy |
| `app/domain/` | use case list + what each does, no Android deps allowed here |
| `app/ui/` | screen list, navigation graph, theming tokens |
| `landing/` | how to run/deploy, where to swap APK/Play Store links |
| `docs/` | how spec/schema/screens stay in sync when something changes |

Each README is short — purpose, how to run, key files. No filler.

## 3. Screen Layouts (mobile, portrait)

### 3.1 Library
```
┌─────────────────────────────┐
│  Bookgram            [+]    │  ← glass top bar, blurred
│                               │
│  ┌────┐ ┌────┐ ┌────┐       │
│  │cover│ │cover│ │cover│     │  ← 3-col grid, rounded covers
│  │ 62% │ │ 10% │ │ new │     │     progress chip bottom-left
│  └────┘ └────┘ └────┘       │
│  ┌────┐ ┌────┐ ┌────┐       │
│  │cover│ │cover│ │cover│     │
│  └────┘ └────┘ └────┘       │
│                               │
│  [ Library ]  [ Feeds ]      │  ← glass bottom tab bar
└─────────────────────────────┘
```

### 3.2 Reader
```
┌─────────────────────────────┐
│ ← Title            ⋯        │  ← translucent, fades on scroll
│                               │
│   Chapter text flows here.   │
│   Selecting text shows a     │
│   floating glass menu:       │
│   [Highlight] [Bookmark]     │
│   [Note]                     │
│                               │
│                               │
│  ●●●○○○○  32%                │  ← progress dots, bottom
└─────────────────────────────┘
```

### 3.3 Bookgram Feed
```
┌─────────────────────────────┐
│  Feed: "Stoic mornings"  ⋯  │
│ ┌───────────────────────┐   │
│ │  [book cover thumb]    │   │
│ │                         │   │
│ │  "The impediment to     │   │  ← glass card, full-bleed
│ │   action advances       │   │     quote centered, serif
│ │   action."               │   │
│ │                         │   │
│ │  — Meditations           │   │
│ │  ♡ 12   🔖   ↗            │   │
│ └───────────────────────┘   │
│         ▼ swipe up           │
└─────────────────────────────┘
```

### 3.4 Feed Builder
```
┌─────────────────────────────┐
│  New Feed               ✓   │
│                               │
│  Name: [___________]         │
│                               │
│  Select books:                │
│  ☑ Meditations                │
│  ☑ Letters from a Stoic       │
│  ☐ Atomic Habits              │
│                               │
│  [ Generate Feed ]            │  ← primary glass button
└─────────────────────────────┘
```

## 4. Build/Run Notes
- `app/` → open in Android Studio, run on device/emulator, `./gradlew assembleDebug` for APK
- `landing/` → `npm install && npm run dev`, deploy static build wherever (Vercel/Netlify/GH Pages)
- Keep `docs/screens.md` as the single reference so UI doesn't drift from this layout
