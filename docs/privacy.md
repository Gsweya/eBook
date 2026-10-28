---
title: GBook privacy policy
---

# Privacy policy

*Last updated 28 October 2026*

GBook is an open-source eReader for Android and the desktop. This policy explains what happens to your
information when you use it.

## The short version

GBook has no server and no account of its own. It doesn't collect analytics, show ads, or send your data to
anyone. Your books and reading data stay on your devices and, if you turn on sync, in your own Google Drive.
If you choose to look words up online, the words you look up are sent to a dictionary service (see
[Dictionary](#dictionary)).

## Data on your device

Your books, highlights, notes, posts, collections, reading plans, reminders, settings, profile name and
picture, and reading statistics are stored only on the device you use GBook on. Backups you make are files
you choose where to save.

## Google Drive sync

Sync is optional and off until you sign in with Google. When it is on, GBook uses these Google permissions:

- **See, create, and delete its own configuration data in your Google Drive** (`drive.appdata`). GBook keeps
  a sync file (your reading positions, highlights, notes, posts, collections, plans, reminders, settings and
  reading time) and, if "Sync book files" is on, copies of your book files in a hidden folder in your Drive
  that only GBook can access. It cannot see or change any other file in your Drive.
- **See your primary Google Account email address** (`email`). GBook shows it in its settings so you know
  which account you are syncing with. It is not used for anything else.

This data travels directly between your devices and Google, over encrypted connections. The developer of
GBook never receives it. Sign-in tokens are kept by Google Play services on Android and in your system's
keyring on the desktop.

GBook's use and transfer of information received from Google APIs adheres to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements. GBook does not use this information for advertising, does not sell
it, and does not transfer it to anyone.

## Dictionary

Looking a word up is optional, and the first time you do it GBook asks how:

- **Download the dictionary:** GBook downloads Princeton's WordNet once from the npm package registry
  (registry.npmjs.org) and looks words up on your device from then on. Nothing you look up leaves your device.
- **Look words up online:** each word you look up is sent to the
  [Free Dictionary API](https://dictionaryapi.dev) to get its definition. Only the word is sent, with no account
  or other information from GBook.

You can switch between them, or remove the downloaded dictionary, in Settings › Reading.

## Removing your data

- **Stop syncing:** sign out in GBook's settings. Your library stays on the device.
- **Delete the synced copy:** in Google Drive on the web, open Settings › Manage apps › GBook › Delete hidden
  app data.
- **Revoke access:** at [myaccount.google.com/permissions](https://myaccount.google.com/permissions).
- **Delete everything on a device:** uninstall GBook, or clear its data.

## Children

GBook is not directed at children under 13 and does not knowingly collect their information.

## Changes

If this policy changes, the new version will be posted on this page with a new date.

## Contact

Questions about privacy: open an issue at
[github.com/Gsweya/eBook/issues](https://github.com/Gsweya/eBook/issues).
