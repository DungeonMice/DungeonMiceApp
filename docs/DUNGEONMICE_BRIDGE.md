# Connecting the real DungeonMice Python app

All adapter files live in the Java repository on branch `api`. No existing file in
`F:\JavaApps\DungeonMice` needs to be edited. RunExperiment, MouseTracker, EventLogic,
ExperimentVisualizer, the JSON loader and labyrinth report generation are imported
from that project, not copied or reimplemented.

## Start

Run Java's `App` from IntelliJ or Maven. Java starts the real Python adapter in
the background, chooses an available loopback port and connects the UI
automatically. There is no separate terminal or URL to enter. Processing is
disabled until the service reports ready; startup errors appear in the Python
panel without preventing the rest of the application from opening.

Closing Java cancels its transfers and shuts down only its own adapter and
analysis workers, including when closing during startup. Other local services
are not stopped. The shutdown endpoint requires a per-launch ownership token.

The Python project defaults to the Java project's sibling `DungeonMice`. The
existing isolated environment is discovered automatically, including Windows'
Codex package cache redirect. Python and its existing `requirements.txt`, VLC
and FFmpeg still need to be installed; this change does not bundle an installer.
All adapters remain in the Java repository, not in the Python source tree.

Optional IntelliJ VM options:

```text
-Ddungeonmice.python.project=F:\JavaApps\DungeonMice
-Ddungeonmice.python.executable=C:\my-venv\Scripts\python.exe
-Ddungeonmice.python.config=F:\JavaApps\DungeonMice\src\CrossMaze_control_s1.json
```

`DUNGEONMICE_PYTHON` can also select the interpreter. The application root is
discovered from the working directory or class/JAR location; override it with
`-Ddungeonmice.app.root=F:\JavaApps\DungeonMiceApp` when needed.

Choose a video and **Procesar video**, then an external NEW `.mp4` destination.
The output is always MP4, even when the original is MOV.

## Optional Manual Server

```powershell
& F:\JavaApps\DungeonMiceApp\scripts\start-dungeonmice-api.ps1
```

The launcher defaults to the isolated environment created at
`$env:LOCALAPPDATA\DungeonMice\api-venv`, the Python project at
`F:\JavaApps\DungeonMice`, and port 8765.

If Windows redirected the Codex-created environment into its package's local
cache, the launcher also checks that location. `-PythonPath` can always select an
explicit existing environment.

For a different environment or preset:

```powershell
& F:\JavaApps\DungeonMiceApp\scripts\start-dungeonmice-api.ps1 -PythonPath C:\my-venv\Scripts\python.exe -ConfigPath F:\JavaApps\DungeonMice\src\CrossMaze_control_s1.json
```

For manual/external mode, set Java's VM option
`-Ddungeonmice.python.url=http://127.0.0.1:8765`, or disable automatic startup with
`-Ddungeonmice.python.autostart=false` and enter the URL in the interface. Java
will not start or shut down that external service. Do not run the old echo
example server on the same port.

## Behavior

- The Java Regions panel now supports adding, drawing, naming, recoloring,
  redrawing, editing and deleting polygons. Select **Agregar**, click the
  vertices on the paused video and choose **Guardar region**. At least three
  vertices form a polygon; the undo control removes the last vertex. Save or
  cancel the current draft before processing.
- Regions are persisted per video in the existing encrypted database. Only
  geometry and settings are stored, never video copies. Switching videos loads
  that video's own regions. Color is a Java editor setting; Python retains its
  original annotation colors. The overlap slider is passed as
  `overlap_threshold` in the original Python format.
- Coordinates use the original frame's pixel resolution, independent of window
  resizing, zoom, pan or VLC buffer padding. Resolution changes, duplicate names,
  crossed edges, out-of-bounds points and zero-area polygons are rejected. Java
  requires two saved polygons before submitting the current CrossMaze analysis.
- For CrossMaze's existing open/closed arm summary, use identifiers `este`,
  `oeste`, `norte` and `sur` as appropriate. Custom identifiers work for individual
  region metrics, but do not join the original open/closed arm classification.
- Processing displays progress such as `300/927 frames` and the final output
  generation phase. A full result is returned after analysis, not progressively
  displayed frames. Python validates custom regions before accepting the video
  upload and reports validation errors to Java instead of hiding the reason.
- The inspector separates **Regiones** and **Registros** into tabs, while the
  processing controls remain visible below. Video opening has an explicit loading
  indicator. Session logs are searchable, bounded to 200 entries per original,
  include timecodes, and survive navigation within the workspace. They are not
  scientific reports and are not persisted after closing the application.
- Completed videos open in **Procesados** and remain accessible after switching
  tabs or restarting Java. The encrypted database stores external result paths
  and the original analysis region snapshot, not video data. **Ver resultado**
  retrieves the selected original's latest indexed output. Existing older outputs
  can be linked manually with **Vincular resultado** without creating copies.
- The HTTP contract is unchanged: POST metadata, PUT original video, receive result.
- Empty regions from legacy manual clients use the operator-selected preset
  (CrossMaze_control_s1.json by default); the new UI sends saved regions instead.
  This is NOT automatic maze/geometry detection; select the correct
  preset and parameters for your experiment before relying on scientific results.
- Supplied regions override the preset. Polygon points can be Java `{x,y}` objects
  or native `[x,y]` pairs; native CircleRegion/CircularFractionRegion objects pass
  through to the original loader for validation.
- Each job runs the ORIGINAL `RunExperiment.process_single_video` in a separate
  worker process. Only that worker's RunExperiment display binding is redirected:
  its annotated `frame` images are written to an MP4 instead of opening OpenCV
  windows. The tracker and scientific event logic remain unchanged.
- The result is a full MP4 (H.264 video with a silent AAC clock track), not an echo of the input. On
  Windows, the adapter uses the Media Foundation encoder through OpenCV; it needs
  a working H.264 encoder. FFmpeg must be on PATH to mux the clock track without
  re-encoding the annotated video. Original sound is not analyzed or returned. There
  is still NO frame-by-frame HTTP streaming.
- Existing Excel/trajectory/heatmap generation runs inside the job's OS temporary
  directory. The server logs a frame/region/tracking summary. Only the video is
  returned by API v1; reports and working files are deleted after the response.
  Retrieval/persistence of analysis reports is a separate future API change.
- One analysis job runs at a time. Overlapping uploads receive HTTP 503. A Java
  disconnect or a timeout terminates its worker and cleans its temporary directory.
- No videos, caches, settings or `__pycache__` are written to the Python project.
  Neither source files nor the original preset JSON are modified.
- The adapter preserves existing algorithm behavior, including its current warmup,
  integer FPS calculation and handling of `end_time`. It does not repair or alter
  scientific logic during integration.

## Project-wide regions and batch processing

In Video > Regiones, **Aplicar a todos** copies the saved polygons of the current
video into the other videos in the selected project. By default existing regions
are kept, preserving individual exceptions. Selecting **Reemplazar tambien las
regiones existentes** explicitly replaces them. Copies are independent and can
be edited or removed per video afterwards. All region changes persist together.
Coordinates remain in original pixels: templates require the same resolution
and framing. The Python adapter rejects incompatible dimensions; it never
silently scales polygons or analyzes with mismatched coordinates.

**Procesar todos** takes a snapshot of every video's own saved regions (at least
two per video) and asks once for an external output directory. Videos are sent
sequentially to the same Python API. Existing results are not overwritten;
duplicate basenames receive numeric suffixes. Each completed result is immediately
indexed in **Procesados**, including the regions used for that run. Failure of
one video is logged against that video and the remaining videos continue. Cancel
or navigation stops the current analysis and does not start further videos;
already completed results remain available. No original videos are moved or
copied into either repository. `scripts/check-api.ps1` includes batch transport,
failure, cancellation, ownership and independent-region persistence checks.

## Connection files

`scripts/dungeonmice_api_bridge.py` is the real processing adapter.
`scripts/start-dungeonmice-api.ps1` starts it with the selected environment/preset.
The existing `scripts/python_api_example.py` supplies the HTTP transport only.
`ManagedPythonBridge` owns automatic startup/shutdown from Java.
`scripts/check-python-autostart.ps1` verifies lifecycle and actual `App` startup
against a disposable database. Its optional `-TestVideo` verifies shutdown
during real analysis without retaining the result.
`scripts/check-functional.ps1` includes drawing/editing, coordinate mapping and
database reload checks, plus processed-library navigation, owner isolation,
missing-file handling, log filtering and compact/large UI screenshots.
`scripts/check-dungeonmice-bridge.ps1 -CustomRegions`
tests the real Python engine with regions created through Java controls on a
CrossMaze clip, checks the returned region identifiers, and removes all test
videos and results afterwards.

Video encoding uses OpenCV's documented VideoWriter interface:
https://docs.opencv.org/4.x/dd/d9e/classcv_1_1VideoWriter.html.
