# Dungeon Mice for Linux x86_64

This portable archive contains the `flag` application, a Linux Java 17 runtime,
Linux JavaFX libraries and an isolated Python 3.14.8 runtime with OpenCV-contrib,
NumPy and OpenPyXL. No system Java, Python or Maven is needed to run it.
The original Python engine is bundled without edits. Personal databases and
videos are not included. Keep the complete extracted directory together.

Target: a graphical Linux x86_64 desktop with glibc 2.28 or newer. Ubuntu 22.04
and 24.04 are reference targets. This is not an ARM/Raspberry Pi, Alpine/musl,
macOS or Windows package. Runtime verification on the target Linux system is
required; the cross-platform builder cannot execute Linux programs on Windows.

## Single-File Installer

Copy `DungeonMice-1.0.8-linux-x86_64.run` to the Linux computer and run:

```sh
sh DungeonMice-1.0.8-linux-x86_64.run
```

Run it as your normal user, without sudo. It verifies and extracts its own
embedded payload, installs dependencies, copies the app to a permanent location
and registers it in the application menu. It asks for sudo only for apt packages.
Its private extraction folder is removed when it finishes, including on failure.
You may discard the `.run` file after installation. The tarball below remains
available for portable use or manual extraction; you do not need both downloads.

## Install From the Tarball

Extract on Linux so permissions and symbolic links are preserved:

```sh
tar -xzf DungeonMice-1.0.8-linux-x86_64.tar.gz
cd DungeonMice
./instalar.sh
```

Run `instalar.sh` as your normal user, without sudo. It installs the system
dependencies, copies the program to `${XDG_DATA_HOME:-$HOME/.local/share}/dungeon-mice-app`
and adds Dungeon Mice to the application menu with the mouse icon. Launch it from
the menu after installation. The extracted source folder may then be discarded.
Close Dungeon Mice before running the installer again to update an existing copy.
The separate `DungeonMice` user-data folder and external videos are preserved.

The dependency step uses apt on Ubuntu/Debian and requests administrator
permission to install VLC, FFmpeg and the GTK/GL/ALSA desktop libraries. It
does not install Java/Python or modify Dungeon Mice's data. On other desktop
distributions, install the equivalent system libraries and VLC manually.
FFmpeg is used only by optional full MP4 export. Live analysis uses OpenCV.
MP4 export feeds annotated frames directly into FFmpeg's libx264 encoder; it
does not require the OpenCV wheel to include an H.264 encoder.
An internet connection is needed for apt if these dependencies are missing.

Alternatively, run the extracted folder without copying it:

```sh
./install-dependencies.sh
./setup.sh
./DungeonMice
```

In that portable mode, the shortcut points to the extracted folder; keep it at
the same location. The one-script install uses its own permanent program folder.
## Uninstall on Debian/Ubuntu

Close Dungeon Mice, then run as the same user who installed it, without sudo:

```sh
sh "${XDG_DATA_HOME:-$HOME/.local/share}/dungeon-mice-app/desinstalar.sh"
```

You can also run `sh desinstalar.sh` from the newly extracted tarball. Confirm
the prompt, or add `--yes` for unattended removal. The uninstaller removes only
the validated installed program directory and its managed application-menu entry.
It refuses foreign files, symlink destinations and an active application/backend.
Databases, profile photos, event snapshots, reports and original videos remain.
Shared VLC/FFmpeg/system libraries are not removed. An extracted portable folder
is not automatically deleted. `./setup.sh --uninstall` invokes the same removal.

## Verify on Linux

```sh
./check-linux.sh
./check-linux.sh --ui
```

The first command checks runtime versions, native imports/tracker, VLC loading
and original Python engine hashes. `--ui` additionally opens/closes JavaFX and
its automatic Python service with temporary data and from another working
directory. Run it from a graphical session (DISPLAY or a working desktop).
Try playback, region selection and post-analysis event review with real videos
before distributing the package to other users.

Database, profiles and event snapshots are stored in
`${XDG_DATA_HOME:-$HOME/.local/share}/DungeonMice`, outside this folder. The
database is independent of your Windows database. Original videos remain path
references; Linux projects need paths accessible from that Linux machine.

## Rebuild

On Windows, from the Java repository:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-linux.ps1
```

On Linux, use builder Python 3.11+ and a JDK 17+ with Maven:

```sh
python3 -B scripts/build-linux.py --python-project ../DungeonMice
```

Build dependencies are downloaded once and checked against the SHA-256 values
in `scripts/linux-bundle-lock.json`. The builder compiles a source-only temporary
copy with `javafx.platform=linux`; the Windows app, installer and target output
are left in place. Linux runtime archives are streamed into the output with
their Unix executable permissions and relative links preserved. No Linux native
code is executed during a Windows build. Output and checksum:

```
dist/linux/DungeonMice-1.0.8-linux-x86_64.tar.gz
dist/linux/DungeonMice-1.0.8-linux-x86_64.tar.gz.sha256
dist/linux/DungeonMice-1.0.8-linux-x86_64.run
```

Use `--version 1.0.9` for another version or `--force` to explicitly replace an
existing archive of the same version. Use `--cache`, `--output`, `--java-home`
and `--python-project` to override builder locations. Downloads remain in a
per-user build cache; source videos and personal databases are never scanned
for packaging. See THIRD_PARTY.md and bundled notices for runtime licenses.

Sources:
- https://github.com/astral-sh/python-build-standalone/blob/main/docs/running.rst
- https://adoptium.net/temurin/releases/
- https://specifications.freedesktop.org/desktop-entry-spec/latest/
