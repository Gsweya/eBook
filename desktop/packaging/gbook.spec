# Packages a staged install (see build-packages.sh); nothing is compiled here.
%global debug_package %{nil}

Name:           gbook
Version:        %{gbook_version}
Release:        1%{?dist}
Summary:        Read, highlight and remember your books
License:        MIT AND OFL-1.1 AND Apache-2.0
URL:            https://github.com/Gsweya/eBook
Requires:       gtk4 >= 4.14
Requires:       poppler-glib
Requires:       fontconfig

%description
GBook is a lightweight reader for EPUB and PDF books with highlights, notes, a feed of
ideas from your books and reading stats. Its library is interchangeable with GBook on Android.

%install
cp -a %{stage}/. %{buildroot}/

%files
%license /usr/share/licenses/gbook/LICENSE
/usr/bin/gbook
/usr/share/applications/io.github.gsweya.GBook.desktop
/usr/share/icons/hicolor/scalable/apps/io.github.gsweya.GBook.svg
/usr/share/metainfo/io.github.gsweya.GBook.metainfo.xml
/usr/share/gbook/
