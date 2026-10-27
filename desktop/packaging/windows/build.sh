#!/usr/bin/env bash
# Builds GBook for Windows in an MSYS2 UCRT64 shell and lays it out with the parts of GTK it needs:
#   dist/windows/GBook/            the app, ready to run (bin\gbook.exe)
#   dist/GBook-<version>-windows-portable.zip
# The installer is then made from dist/windows/GBook with gbook.iss (Inno Setup).
#
# One-time setup in MSYS2 UCRT64:
#   pacman -S --needed git zip mingw-w64-ucrt-x86_64-{rust,gcc,pkgconf,gtk4,poppler,librsvg,adwaita-icon-theme}
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
desktop="$(cd "$here/../.." && pwd)"
repo="$(dirname "$desktop")"
prefix="${MINGW_PREFIX:-/ucrt64}"
version="$(sed -n 's/^version = "\(.*\)"/\1/p' "$desktop/Cargo.toml" | head -1)"
out="$repo/dist/windows/GBook"

cd "$desktop"
cargo build --release
rm -rf "$out"
mkdir -p "$out/bin"
cp target/release/gbook.exe "$out/bin/"

# Copies the MSYS2 DLLs a binary or DLL depends on (recursively, via ldd) into bin/.
copy_deps() {
    ldd "$1" | awk '{print $3}' | grep -i "^$prefix/" | while read -r dll; do
        if [ ! -f "$out/bin/$(basename "$dll")" ]; then
            cp "$dll" "$out/bin/"
        fi
    done
}
copy_deps "$out/bin/gbook.exe"

# Image loaders (covers, the SVG app icon), with their own DLLs.
loaders="lib/gdk-pixbuf-2.0/2.10.0"
mkdir -p "$out/$loaders/loaders"
cp "$prefix/$loaders/loaders/"*.dll "$out/$loaders/loaders/"
for l in "$out/$loaders/loaders/"*.dll; do copy_deps "$l"; done
# Listed with paths relative to the install folder, which gdk-pixbuf resolves wherever GBook is installed.
# The tool writes absolute paths; strip the build folder so they're relative again.
win_out="$(cygpath -m "$out")"
(cd "$out" && gdk-pixbuf-query-loaders "$loaders"/loaders/*.dll) \
    | sed -e "s|$win_out/||g" -e "s|$out/||g" \
    > "$out/$loaders/loaders.cache"
if grep -q "$win_out" "$out/$loaders/loaders.cache"; then
    echo "loaders.cache still has absolute paths" >&2
    exit 1
fi

# GTK's settings schemas, its fallback icons and fontconfig's configuration.
mkdir -p "$out/share/glib-2.0/schemas"
cp "$prefix/share/glib-2.0/schemas/"org.gtk.gtk4.*.xml "$out/share/glib-2.0/schemas/" 2>/dev/null || true
glib-compile-schemas "$out/share/glib-2.0/schemas"
mkdir -p "$out/share/icons/Adwaita" "$out/share/icons/hicolor"
cp "$prefix/share/icons/Adwaita/index.theme" "$out/share/icons/Adwaita/"
cp -r "$prefix/share/icons/Adwaita/symbolic" "$out/share/icons/Adwaita/"
cp "$prefix/share/icons/hicolor/index.theme" "$out/share/icons/hicolor/"
mkdir -p "$out/etc"
cp -r "$prefix/etc/fonts" "$out/etc/"

# GBook's own fonts, icons and starter book, as on Linux.
"$desktop/packaging/install.sh" "$out" --no-binary
cp "$here/gbook.ico" "$out/"

cd "$repo/dist"
rm -f "GBook-$version-windows-portable.zip"
(cd windows && zip -qr "../GBook-$version-windows-portable.zip" GBook)
echo "Built $out and dist/GBook-$version-windows-portable.zip"
