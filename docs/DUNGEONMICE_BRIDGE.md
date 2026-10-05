# Connecting the real DungeonMice Python app

All adapter files live in the Java repository on branch `api`. No existing file in
`F:\JavaApps\DungeonMice` needs to be edited. RunExperiment, MouseTracker, EventLogic,
ExperimentVisualizer, the JSON loader and labyrinth report generation are imported
from that project, not copied or reimplemented.

## Start

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

The environment needs the existing Python app's `requirements.txt`. Do not run the
old echo example server on the same port. In Java, run `App`, choose a video and
**Procesar video**, then choose an external NEW destination ending in `.mp4`.

## Behavior

- The HTTP contract is unchanged: POST metadata, PUT original video, receive result.
- Empty Java regions use the operator-selected preset (CrossMaze_control_s1.json by
  default). This is NOT automatic maze/geometry detection; select the correct
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

## New connection files

`scripts/dungeonmice_api_bridge.py` is the real processing adapter.
`scripts/start-dungeonmice-api.ps1` starts it with the selected environment/preset.
The existing `scripts/python_api_example.py` supplies the HTTP transport only.

Video encoding uses OpenCV's documented VideoWriter interface:
https://docs.opencv.org/4.x/dd/d9e/classcv_1_1VideoWriter.html.
