# Functional Verification

From the project root on Windows, run:

```powershell
./scripts/check-functional.ps1
```

Requires JDK 17, Maven, FFmpeg and FFprobe on PATH. An explicit JDK can be supplied using `-JavaPath`.
The script compiles the project, generates four-second MP4 and AVI fixtures, and runs the real JavaFX application checks.
All accounts, projects, imports and persistence checks use a fresh temporary encrypted database. User data is never loaded or changed.
Screenshots are written to `target/functional-snapshots`.

Covered: legacy password/data compatibility, registration, authentication, duplicates, profile/password updates, project ownership and CRUD, encrypted reload, MP4 import, AVI conversion and previews, real thumbnails, UI account/project workflows, decoded video pixels, playback/seek/pause/volume, zoom layout stability, and disposal during navigation.
The JavaFX integration checks are separate from Maven's default unit-test lifecycle; `mvn test` alone does not launch them.

Window checks exercise button actions on a real undecorated stage: maximize/restore bounds, minimize, icon input transparency, double-click isolation, controls during busy content, and shutdown via the close button or native close request. They do not inject operating-system mouse input.
