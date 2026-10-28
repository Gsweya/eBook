# GBook sync format

Phone and desktop sync through the user's own Google Drive, so GBook needs no server. Both apps read and write
the same files in the Drive *app data folder* (hidden, per app, scope `drive.appdata`):

| Drive file              | What it holds                                                    |
|-------------------------|------------------------------------------------------------------|
| `gbook-sync.json.gz`    | The sync document below, gzip-compressed JSON                    |
| `book-<id>.epub` / `.pdf` | A book's file, when "Sync book files" is on; `<id>` is the book id |

Book ids are the first 12 hex digits of the file's SHA-1 on both platforms, so the same file is the same book
everywhere. Covers, `content.json` and images are not uploaded: each device rebuilds them from the book file.

The Kotlin (`gbook/.../data/Sync.kt`) and Rust (`desktop/core/src/sync.rs`) implementations must agree; the cases
in `cases/` are run by both test suites.

## The sync document

```json
{
  "format": 1,
  "books":       { "<id>":     Entity },
  "highlights":  { "<id>":     Entity },
  "posts":       { "<id>":     Entity },
  "collections": { "<id>":     Entity },
  "reminders":   { "<id>":     Entity },
  "plans":       { "<bookId>": Entity },
  "settings":    Entity,
  "counters":    { "<device>": Counters }
}
```

An **Entity** holds every synced field of one item with the time it was last set and the device that set it,
plus the time it was deleted, if it was:

```json
{ "f": { "title": [1730000000000, "a1b2c3d4", "Dune"], "rating": [1730000500000, "9f8e7d6c", 4] },
  "del": [1730000900000, "a1b2c3d4"] }
```

A **stamp** is `[ms, device]`; stamps compare by `ms`, then by `device` as a plain string. An entity is *alive*
when it has no `del`, or when some field's stamp is greater than `del`. Fields are kept after a deletion, so an
item edited elsewhere after it was deleted comes back whole.

**Counters** are each device's own contribution to the reading statistics. A device only ever adds to its own
entry; the totals the apps show are the sum over devices:

```json
{ "readingLog": { "2026-10-28": 1800000 }, "pagesLog": { "2026-10-28": 12 },
  "bookDays": { "2026-10-28": { "<bookId>": 1800000 } },
  "readingMs": { "<bookId>": 1800000 }, "pagesRead": { "<bookId>": 12 } }
```

## Which fields sync

The library is `library.json` (`LibraryData`). Lists become entity maps keyed by `id` (`plans` by `bookId`).

- **Not synced (per device):** every top-level field other than the lists, `settings` and the three logs
  (e.g. `seeded`, `onboarded`, `lastBackupAt`, `goalReachedDay`); in books `hasCover`, `coverColor`,
  `chapterCount`, `pageCount`, `textReady`, `textVersion`, `backedUpAt`; in settings `fontSize`, `margin` and
  `avatarVersion` (text size suits the screen; profile pictures don't sync yet).
- **Counters:** `readingLog`, `pagesLog`, `bookDays`, and each book's `readingMs` and `pagesRead`.
- **Self-stamped** book fields carry the time they really changed rather than the time of the sync, so the
  device that read most recently wins: `position`, `progress` and `positionAt` are stamped with `positionAt`
  (falling back to `lastOpenedAt` for books read before sync existed), and `lastOpenedAt` with its own value.
  Apps set `positionAt` to the current time whenever the position or progress actually changes.
- Everything else, including fields one app doesn't know yet, syncs field by field, last change wins.

## A sync

Each device keeps, next to its library, its device id (8 random hex digits), its own copy of the document
(`doc`) and the library exactly as it was after the last sync (`base`, absent before the first sync). A sync is:

1. **Pull** the Drive document, if it changed since the last sync, and **join** it into `doc`.
2. **Record** what changed in the library since `base` into `doc`.
3. **Project** `doc` back onto the library and save that as the new library and the new `base`.
4. **Files:** download and import books that are new to this device, then record and project again (step 2–3
   with nothing pulled) so the imported books take the account's reading state; upload books Drive doesn't
   have; delete the Drive files of deleted books.
5. **Push** `doc` if it differs from what was pulled.

Two devices pushing at once can overwrite each other on Drive, but nothing is lost: each keeps its own `doc` and
joins it again next time.

### Join

Union of all maps. Per field, the value with the greater stamp wins (equal stamps keep the existing value);
`del` is the greater of the two. Counters: per device and key, the greater number. `format` is the greater.

### Record

`now` is the device clock and `S = [now, device]`. For every list, with `l` the local item, `b` the item in
`base` and `e` the entity in `doc`:

- `e` is missing: a new item; write all its fields (below).
- `l` and `b` exist, `e` is alive: set each field of `l`, other than self-stamped ones, that is missing from `b`
  or differs from it, stamped `S`. A field missing from `l` never counts as a change (an app drops fields it
  doesn't know). A highlight's `note` that another device also changed (its value in `e` differs from `b`)
  is not replaced: both texts are kept, the other device's first, separated by a blank line (just one of
  them when either is empty or already contains the other).
- `l` and `b` exist, `e` is deleted: if any synced field differs from `b`, the item was edited after it was
  deleted elsewhere; write all its fields (below). Otherwise do nothing; the deletion applies.
- `l` exists, `b` doesn't, `e` is alive: the account already has this item (a first sync, or a book imported
  again). Nothing is overwritten: only fields the account has left *empty* (null, false, 0, "", [] or {})
  and that aren't empty here are set, stamped `S`.
- `l` exists, `b` doesn't, `e` is deleted: added again here (a book imported again); write all its fields. On a
  first sync, though, an item the account has deleted stays deleted.
- `b` exists, `l` doesn't, `e` is alive: deleted here; set `del` to `[max(now, newest field ms + 1), device]`.

*Writing all fields* stamps them `S`, except self-stamped fields, which take their own stamp when its `ms` is
above 0. Afterwards, for each local book whose entity is alive, each self-stamped field with an own stamp above
0 is set if that stamp is greater than the field's stamp in `doc`.

`settings` is one entity that is never deleted: diffed against `base` like an item; on a first sync it is
written whole when `doc` has no settings yet, and otherwise only fills empty fields, like an item.

Before applying a sync that changes the library, each app keeps the library as it was in
`sync/history/library-<ms>.json`, the last 10 times, so nothing a sync replaced is lost for good.

**Counters**, for each key (a day, a book, or a day and book): `before` is the value in `base`, or on a first
sync the current total over all devices in `doc`. If `local - before > 0`, add the difference to this device's
counter. On a first sync this keeps the larger of the two figures, so a library restored from a backup of the
same history isn't counted twice.

### Project

Start from the local library, which keeps every per-device field, then for each list:

- **books:** alive entities whose book is in the local library (books whose file isn't here yet wait until it
  has been imported). The local item with the entity's fields laid over it, except per-device fields.
  `readingMs` and `pagesRead` are the counter totals.
- **highlights, posts, plans:** alive entities whose `bookId` is a projected book.
- **reminders:** alive entities with no `bookId`, or a projected one. **collections:** all alive entities.
- Items new to this device are built from the entity's fields alone. Order: the local order for items already
  here; new posts first, newest `createdAt` first; other new items last, by `createdAt` (`addedAt` for books),
  then id.
- **settings:** the local settings with the entity's fields over them, except per-device fields.
- **readingLog, pagesLog, bookDays:** the totals over all devices.
