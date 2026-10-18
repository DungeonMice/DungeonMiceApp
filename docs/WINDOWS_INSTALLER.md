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
Only generated app/runtime/launcher files are updated; existing database folders,
original videos and VLC installer files are not deleted or copied into the JAR.
Temporary jpackage output is removed after the build, and `dist` is ignored by Git.
This produces the Java executable, not the final Inno Setup installer, and does
not silently bundle Python or claim a complete production-ready distribution.

## Per-User Data

Default database: `%LOCALAPPDATA%\DungeonMice\data\database.enc`.
Profile photos: `%LOCALAPPDATA%\DungeonMice\data\profiles`.
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

The current Java-only distribution is **not a complete production package**:
the Python engine/interpreter/dependencies still need packaging, production
credentials need setup, and installation/update/uninstallation must be checked
on a clean Windows system. This script does not solve these remaining items.
