#!/usr/bin/env bash
# Builds a release binary and packs it as dist/gbook_<version>_<arch>.deb and dist/gbook-<version>-1.<arch>.rpm.
# Needs dpkg-deb and/or rpmbuild; either package is skipped if its tool is missing.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
desktop="$(dirname "$here")"
dist="$(dirname "$desktop")/dist"
cd "$desktop"
version="$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)"
cargo build --release
mkdir -p "$dist"
stage="$desktop/target/package/root"
rm -rf "$desktop/target/package"
"$here/install.sh" "$stage/usr"

if command -v dpkg-deb >/dev/null; then
    arch="$(dpkg --print-architecture 2>/dev/null || echo amd64)"
    mkdir -p "$stage/DEBIAN"
    cat > "$stage/DEBIAN/control" <<CONTROL
Package: gbook
Version: $version
Architecture: $arch
Maintainer: Gsweya <https://github.com/Gsweya/eBook/issues>
Installed-Size: $(du -sk "$stage/usr" | cut -f1)
Depends: libgtk-4-1 (>= 4.14), libpoppler-glib8 | libpoppler-glib8t64, fontconfig
Section: text
Priority: optional
Homepage: https://github.com/Gsweya/eBook
Description: Read, highlight and remember your books
 A lightweight reader for EPUB and PDF books with highlights, notes, a feed of
 ideas from your books and reading stats. Its library is interchangeable with
 GBook on Android.
CONTROL
    dpkg-deb --root-owner-group -Zxz --build "$stage" "$dist/gbook_${version}_${arch}.deb" >/dev/null
    rm -rf "$stage/DEBIAN"
    echo "Built $dist/gbook_${version}_${arch}.deb"
fi

if command -v rpmbuild >/dev/null; then
    top="$desktop/target/package/rpm"
    rpmbuild -bb --quiet --define "_topdir $top" --define "stage $stage" --define "gbook_version $version" "$here/gbook.spec"
    cp "$top"/RPMS/*/gbook-*.rpm "$dist/"
    echo "Built $(ls "$dist"/gbook-"$version"-*.rpm)"
fi
