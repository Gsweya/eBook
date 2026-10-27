#!/usr/bin/env bash
# Installs a release build under a prefix: packaging/install.sh /usr/local  (or a staging dir's /usr).
# With --no-binary, only the shared data (fonts, icons, starter book…), e.g. for the Windows layout.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
desktop="$(dirname "$here")"
repo="$(dirname "$desktop")"
prefix="${1:?usage: install.sh PREFIX [--no-binary]}"
id=io.github.gsweya.GBook

if [ "${2:-}" != "--no-binary" ]; then
    install -Dm755 "$desktop/target/release/gbook" "$prefix/bin/gbook"
fi
install -Dm644 "$here/$id.desktop" "$prefix/share/applications/$id.desktop"
install -Dm644 "$here/$id.svg" "$prefix/share/icons/hicolor/scalable/apps/$id.svg"
install -Dm644 "$here/$id.metainfo.xml" "$prefix/share/metainfo/$id.metainfo.xml"
# Reading and UI fonts are shared with the Android app; the app registers them itself at startup.
install -d "$prefix/share/gbook/fonts"
install -m644 "$repo"/gbook/app/src/main/res/font/*.ttf "$prefix/share/gbook/fonts/"
install -d "$prefix/share/gbook/icons/hicolor/scalable/actions"
install -m644 "$desktop"/app/data/icons/hicolor/scalable/actions/*.svg "$prefix/share/gbook/icons/hicolor/scalable/actions/"
# The free starter book that's added to every new library, as on Android.
install -Dm644 "$repo/gbook/app/src/main/assets/starter/Fractured Minds.pdf" "$prefix/share/gbook/starter/Fractured Minds.pdf"
install -Dm644 "$repo/LICENSE" "$prefix/share/licenses/gbook/LICENSE"
