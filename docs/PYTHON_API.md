# Java / Python video API v1

This branch implements whole-file exchange only, not frame streaming. The real
adapter runs the existing Python analysis engine without changing its sources.
Java is the HTTP client; Python owns the local server.
It uses JDK 11's HTTP client and the existing Gson dependency, not another framework.

## Start

Run `com.phys.dungeonmiceapp.App` in IntelliJ or `mvn javafx:run`.
Python starts and connects automatically on a free local port. See
[real adapter setup](DUNGEONMICE_BRIDGE.md) for environment and preset options.
Select a project and video, save at least two polygons in **Regiones**, then
choose **Procesar video** in the Python panel.
Select a NEW result filename outside the app directory. Existing files are never
overwritten. The real processor returns an MP4 result.

For transport-only experiments, `python scripts/python_api_example.py` returns
the video unchanged. Select this external server explicitly with Java's VM option
`-Ddungeonmice.python.url=http://127.0.0.1:8765`; automatic startup is then skipped.
The returned file is indexed in the existing encrypted database's
`processedVideos` collection and opens in **Procesados**. Only its external path,
source/project references, registration time and analysis region snapshot are
stored; the video is never copied into the project. **Ver resultado** resolves
the newest indexed result for the selected original, including after navigation
or an application restart. Original video associations remain unchanged.

## Contract

1. `POST /api/v1/jobs`, `Content-Type: application/json; charset=utf-8`:

```json
{
  "protocolVersion": 1,
  "videoId": "video-id",
  "projectId": "project-id",
  "filename": "recording.mov",
  "sizeBytes": 420000000,
  "regions": []
}
```

Response: `201`, JSON `{"jobId":"a-local-job-id"}`. IDs must match
`[A-Za-z0-9_-]{1,128}`. Metadata responses must fit in 64 KiB.
The absolute source path and database/user credentials are not sent.

2. `PUT /api/v1/jobs/{jobId}/video`, `Content-Type: application/octet-stream`,
   `Content-Length: sizeBytes`. The body is the original video, streamed from disk.
   Python processes it and returns `200`, `Content-Type: video/*` or
   `application/octet-stream`, with the complete result as the body.
   Send `Content-Length` for the result, so interrupted/truncated transfers fail.

The real adapter additionally offers `GET /api/v1/jobs/{jobId}/status` while the
job is active, returning JSON such as `{"message":"Procesando: 300/927 frames"}`.
Java polls once per second; older servers without this endpoint still work.
The endpoint reports progress only, not frames, videos or scientific results.

Errors use non-success HTTP status codes and bounded JSON `{"error":"reason"}`
messages. Java displays the reason. No redirects or automatic retries:
retrying processing implicitly could execute a job twice. The Java connect timeout
is 5 seconds; the operation timeout is 30 minutes. Cancellation and navigation
interrupt the background task; unfinished result files are removed. Python must
clean up its work after client disconnects and expire unused job IDs.

## Regions

The UI persists regions per video in the encrypted database and sends a snapshot
of the saved polygons with the job metadata. Coordinates are in ORIGINAL video
pixels, not resized UI pixels. The native Python-compatible polygon format is:

```json
[{"id":"norte","type":"PolygonRegion","points":[[10,20],[120,20],[120,90]],"overlap_threshold":0.8,"color":"#00874b","frameWidth":1280,"frameHeight":720},
 {"id":"sur","type":"PolygonRegion","points":[[200,200],[300,200],[300,300]],"overlap_threshold":0.8,"color":"#bc2042","frameWidth":1280,"frameHeight":720}]
```

IDs are unique region names. Java validates polygon geometry and decoded frame
size, while the adapter checks names, coordinates, thresholds and CrossMaze's
minimum of two polygons before accepting the upload. It then uses the original
Python loader and analysis engine. Width/height detect stale coordinates if the
original file's resolution changes. Color is for the Java overlay only.

Legacy manual clients may send `[]` to request the adapter's configured preset;
the current UI requires saved polygons rather than silently using that fallback.

## Storage and Safety

- Original videos are not copied, converted, edited or loaded entirely into heap.
- A `.part` result is streamed into the user-selected external directory and moved
  to the final filename ONLY after a successful, nonempty video response.
- The **Procesados** library is scoped to the current user's selected project.
  Search filters results without changing files. **Quitar del historial** removes
  only the database reference. Project deletion also drops result references,
  never the external output files. Missing/moved results remain indexed with a
  clear playback error, rather than silently disappearing.
- **Vincular resultado** indexes an existing external result (for example one
  generated before this library existed), associated with an operator-selected
  original video. It never uploads, copies or verifies that an old video contains
  Python annotations. Its region snapshot comes from the original's current
  saved regions and its timestamp is the time of linking, not a recovered
  historical processing time.
- Python's demo uses an OS temporary directory and deletes it after each request.
  This temporary upload is necessary for this whole-video transport, not a Java
  project cache. The real processor owns its own storage policy.
- This first version accepts only loopback endpoints. It is not an authenticated
  remote service. Do not expose the demo server on the LAN or Internet.
- Project data, originals and the encrypted database remain outside this protocol.
