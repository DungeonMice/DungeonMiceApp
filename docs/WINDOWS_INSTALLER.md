# Windows Data and Installer

## Build the Windows EXE

From the project directory, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows.ps1
```

The script runs `mvn clean package` and jpackage with the mouse icon and a bundled
Java runtime. Output: `dist/DungeonMice/DungeonMice.exe`. Keep the whole app-image
directory, not only the EXE. Use `-JavaHome 'C:\path\to\jdk-17'` when needed;
`-SkipBuild` explicitly reuses the existing JAR. Close the app before rebuilding.
Only generated app/runtime/backend/launcher files are updated; existing database folders,
original videos and VLC installer files are not deleted or copied into the JAR.
Temporary jpackage output is removed after the build, and `dist` is ignored by Git.
The app-image now also contains a self-contained Python backend. A build needs
Windows x64, Maven, a JDK 17+, a builder Python with pip (default
`C:\Python314\python.exe`) and the original sibling `DungeonMice` Python checkout.
Override these with `-BuildPython` and `-PythonProject`; the source checkout is
read only and its `RunExperiment_per_frame.py` is copied without modification.

`scripts/package-python.ps1` downloads the official embedded Python 3.14.8,
locked OpenCV-contrib/NumPy/OpenPyXL dependencies and Microsoft VC++ x64 runtime.
`scripts/python-bundle-lock.json` records exact URLs and SHA-256 digests; downloads
are checked before extraction, and the Microsoft prerequisite also needs a valid
Microsoft Authenticode signature. Downloads are cached under
`%LOCALAPPDATA%\DungeonMice\build-cache\python-win64` (override `-CacheDirectory`).
A first build needs internet; a populated verified cache supports offline builds.
pip is used only on the builder to vendor wheels, not on the end user's computer.

Keep `dist/DungeonMice/backend` together with `app`, `runtime` and the EXE. The
backend contains the isolated interpreter, dependency licenses, unchanged engine,
presets, four Java-owned adapter scripts, a source-hash manifest and prerequisite.
No videos, personal database, virtual environment or original checkout path is
included. The installed application uses its own payload even when launched from
another working directory, and reports an incomplete installation instead of
silently falling back to a development checkout. Explicit development overrides
for the interpreter/project are still supported.

Compile `scripts/DungeonMice.iss` in Inno Setup after building the app-image:

```powershell
& 'C:\Program Files\Inno Setup 7\ISCC.exe' scripts\DungeonMice.iss
```

The VLC installer must exist at `dist/vlc-3.0.23-win64.exe` (or inside the app-image
for compatibility). The script refuses an incomplete Python payload, derives its
version from the EXE, installs VC++ only when needed and preserves user data on
updates/uninstallation. Output: `dist/installer/DungeonMiceSetup.exe`.
End users do not need their own Python or the source repositories. VLC is still
installed separately by the setup. Optional MP4 export still requires FFmpeg;
live frame analysis does not need FFmpeg or create a processed video file.

Run `scripts/check-windows-bundle.ps1` after rebuilding to test a relocated copy
outside the checkout, with no host Python on PATH and deliberately invalid
PYTHONHOME/PYTHONPATH. It checks the bundled interpreter/engine, real Morris frame
processing, all three views, bounded cache, lifecycle, missing-payload failure,
unchanged original video and no writes to the installation tree. Use
`-Experiment CrossMaze -VideoDirectory 'C:\path\to\CrossMaze'` for the other engine.

The default build version is 1.0.5; use `-Version` for a subsequent release.
The installer uses the existing yellow mouse logo on its welcome/completion
pages and header. Its square artwork keeps its aspect ratio within the portrait
wizard area; the same mouse icon remains on the setup EXE and shortcuts.

## Per-User Data

Default database: `%LOCALAPPDATA%\DungeonMice\data\database.enc`.
Profile photos: `%LOCALAPPDATA%\DungeonMice\data\profiles`.
Python working directory: `%LOCALAPPDATA%\DungeonMice\analysis`.
The app creates these directories at runtime as the logged-in user. No elevated
permissions or writable application install directory are needed for these files.
The working directory no longer determines the default database location.

`-Ddungeonmice.database=...` still selects an explicit development/test database.
The shared IntelliJ `Dungeon Mice` run configuration explicitly retains the existing
project database and profile storage. Direct App/EXE launches without overrides
use the independent per-user database. `-Ddungeonmice.storage=...` continues to
control legacy relative media resolution and explicit photo storage. Original
videos remain external path references and are never copied into the installer.

Old project and distribution databases are not automatically imported, deleted,
or overwritten. A fresh per-user database creates the existing default account;
this change does not harden account/password management. To migrate an existing
database, close all app instances, back up both databases, and deliberately select
the source before copying it into the per-user location. Keep the same master key
and separately preserve any profile files referenced by the migrated database.
Do not distribute a personal database or overwrite an existing user's database.

`scripts/DungeonMice.iss` excludes root `data` and `videos` trees from the payload,
and extracts VLC only as a temporary prerequisite. Inno Setup removes its installed
program files and shortcuts, not per-user data or the separately installed VLC.
Do not add broad `[UninstallDelete]` rules for AppData or original video folders.

After Java changes, rebuild the JAR and replace the packaged JAR under
`dist/DungeonMice/app` before compiling the installer. The EXE must use the updated
code; changing only the repository source or Inno script is insufficient.

Production credentials still need setup, dependency redistribution requirements
need review, and installation/update/uninstallation must also be checked on a
clean Windows system. Bundling Python does not resolve those separate concerns.
