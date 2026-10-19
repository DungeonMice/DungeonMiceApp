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

## Extract and Run

Extract on Linux so permissions and symbolic links are preserved:

```sh
tar -xzf DungeonMice-1.0.6-linux-x86_64.tar.gz
cd DungeonMice
./install-dependencies.sh
./DungeonMice
```

The dependency script uses apt on Ubuntu/Debian and requests administrator
permission to install VLC, FFmpeg and the GTK/GL/ALSA desktop libraries. It
does not install Java/Python or modify Dungeon Mice's data. On other desktop
distributions, install the equivalent system libraries and VLC manually.
FFmpeg is used only by optional full MP4 export. Live analysis uses OpenCV.
An internet connection is needed for apt if these dependencies are missing.

Optional application-menu shortcut with the yellow mouse icon:

```sh
./setup.sh
```

The shortcut points to this extracted folder; keep it at the same location.
To remove the shortcut, use `./setup.sh --uninstall`. Then remove only the
extracted application folder when no longer needed. User data and external
source videos are kept separately and are not removed by setup/uninstall.

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
dist/linux/DungeonMice-1.0.6-linux-x86_64.tar.gz
dist/linux/DungeonMice-1.0.6-linux-x86_64.tar.gz.sha256
```

Use `--version 1.0.7` for another version or `--force` to explicitly replace an
existing archive of the same version. Use `--cache`, `--output`, `--java-home`
and `--python-project` to override builder locations. Downloads remain in a
per-user build cache; source videos and personal databases are never scanned
for packaging. See THIRD_PARTY.md and bundled notices for runtime licenses.

Sources:
- https://github.com/astral-sh/python-build-standalone/blob/main/docs/running.rst
- https://adoptium.net/temurin/releases/
- https://specifications.freedesktop.org/desktop-entry-spec/latest/
