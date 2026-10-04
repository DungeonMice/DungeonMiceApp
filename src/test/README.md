# Functional Verification

From the project root on Windows, run:

```powershell
./scripts/check-functional.ps1
```

Requires JDK 17, Maven, FFmpeg and FFprobe on PATH. An explicit JDK can be supplied using `-JavaPath`.
The script compiles the project, generates four-second MP4 and AVI fixtures, and runs the real JavaFX application checks.
All accounts, projects, imports and persistence checks use a fresh temporary encrypted database. User data is never loaded or changed.
Screenshots are written to `target/functional-snapshots`.

Covered: legacy password/data compatibility, registration, authentication, duplicates, profile/password updates, project ownership and CRUD, encrypted reload, directory discovery, original video path associations without copying, duplicate references, lazy AVI playback conversion and previews, real thumbnails, UI account/project workflows, decoded video pixels, playback/seek/pause/volume, zoom layout stability, and disposal during navigation.
The JavaFX integration checks are separate from Maven's default unit-test lifecycle; `mvn test` alone does not launch them.

Window checks send JavaFX mouse press/release events at button edges on a real undecorated stage: maximize/restore bounds, minimize, icon input transparency, full-area hit testing, double-click isolation, controls during busy content, and shutdown via the close button or native close request. They do not inject operating-system mouse input.

The script also starts `App.main` with a plain Java classpath and disposable storage, without Maven or module-path options at launch, to cover IDE-style startup.

In IntelliJ, use JDK 17 and run/debug `App.main`, or select the shared `Dungeon Mice` run configuration. The JavaFX lifecycle and window setup are in `DungeonMiceApplication.java`. The run configuration uses the project root as its working directory so the existing local database is loaded.

To test a real video directory as well:

```powershell
./scripts/check-functional.ps1 -CrossMazePath 'C:\Users\Usuario\Downloads\CrossMaze'
```

Real-video checks use temporary storage and leave the source files and application database untouched. They verify full duration, decoded pixels, seeking, play/pause, rapid switching while media is being prepared, and UI heartbeat latency. Compatible MOV files are remuxed without re-encoding. Cache files are published only when preparation succeeds; stale tasks are canceled when changing selection or leaving a view.
