# GBook for Windows

The desktop app is the same Rust + GTK 4 code as on Linux. On Windows it runs with its own copy of GTK, so the
installer carries GTK's DLLs alongside `gbook.exe` (about 30–40 MB installed). Nothing else needs installing.

## How the Windows build works

1. **MSYS2** provides GTK 4, Poppler, Rust and the MinGW compiler for Windows (`UCRT64` environment).
2. `desktop/packaging/windows/build.sh` builds `gbook.exe`, then gathers what it needs into
   `dist/windows/GBook/`:
   - `bin/` — `gbook.exe` and every DLL it loads (found with `ldd`)
   - `lib/gdk-pixbuf-2.0/` — image loaders for covers and the SVG app icon
   - `share/glib-2.0/schemas`, `share/icons/Adwaita` — GTK's settings and fallback icons
   - `etc/fonts` — fontconfig's configuration; the app tells Pango to use fontconfig so its bundled fonts
     (DM Sans, Literata, …) load the same way as on Linux
   - `share/gbook/` — GBook's fonts, icons and starter book
   It also makes `dist/GBook-<version>-windows-portable.zip`, which runs from any folder.
3. **Inno Setup** turns that folder into `GBook-<version>-setup.exe` with `desktop/packaging/windows/gbook.iss`:
   Start menu entry, optional desktop icon, "Open with GBook" for `.epub` and `.pdf`, and an uninstaller. It
   installs per user without admin rights (or for everyone, if chosen).

The library lives in `%APPDATA%\GBook`, with the same `library.json` and backup `.zip` as Linux and Android.

## Building it on GitHub (no Windows machine needed)

`.github/workflows/desktop.yml` does all of the above on GitHub's Windows runner, and builds the Linux `.deb` and
`.rpm` alongside:

- **By hand:** GitHub → Actions → *Desktop packages* → *Run workflow*. The packages appear as downloadable
  artifacts on the run.
- **For a release:** push a tag such as `desktop-v0.1.0`; the packages are attached to that tag's release.

```sh
git tag desktop-v0.1.0
git push origin desktop-v0.1.0
```

## Building it on a Windows PC

1. Install [MSYS2](https://www.msys2.org/) and open **MSYS2 UCRT64** from the Start menu.
2. Install the toolchain and libraries (once):
   ```sh
   pacman -Syu
   pacman -S --needed git zip mingw-w64-ucrt-x86_64-{rust,gcc,pkgconf,gtk4,poppler,librsvg,adwaita-icon-theme}
   ```
3. Build and bundle:
   ```sh
   git clone https://github.com/Gsweya/eBook && cd eBook
   desktop/packaging/windows/build.sh
   ```
   Run `dist/windows/GBook/bin/gbook.exe` to try it.
4. For the installer, install [Inno Setup 6](https://jrsoftware.org/isinfo.php), then in PowerShell from the repo:
   ```powershell
   & "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" /DAppVersion=0.1.0 "/DSourceDir=$PWD\dist\windows\GBook" "/O$PWD\dist" desktop\packaging\windows\gbook.iss
   ```

## Still to do

- **Code signing.** Unsigned installers trigger a SmartScreen warning ("Windows protected your PC" → *More
  info* → *Run anyway*). Signing needs a code-signing certificate; `signtool` can then sign `gbook.exe` and the
  setup in the workflow.
- **Dark mode** follows GBook's own setting on Windows; "System" doesn't read the Windows theme yet.
- An `.msi` (via WiX) if it's needed for managed installs; the Inno Setup `.exe` covers normal use.
