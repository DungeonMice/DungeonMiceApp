# Java / Python video API v1

This branch implements whole-file exchange only. No frame streaming or analysis
algorithm is implemented. Java is the HTTP client; Python owns the local server.
It uses JDK 11's HTTP client and the existing Gson dependency, not another framework.

## Start

```powershell
python scripts/python_api_example.py
```

Run `com.phys.dungeonmiceapp.App` in IntelliJ or `mvn javafx:run`.
Select a project and video, then choose **Procesar video** in the Python panel.
Select a NEW result filename outside the app directory. Existing files are never
overwritten. The default endpoint is `http://127.0.0.1:8765`; the panel allows another
local port, or set `-Ddungeonmice.python.url=http://127.0.0.1:8766`.

The example returns the video unchanged to verify transport and playback. Replace
`process_video(input_path, metadata)` with the real Python processor; return the
path of the resulting video. Choose a result filename with the format/extension
that your processor outputs. The returned file opens in the existing player and
can be reopened with **Ver resultado**. It is NOT automatically added to a project
or the database. The video selection continues to refer to the original file.

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

Errors use non-success HTTP status codes. No redirects or automatic retries:
retrying processing implicitly could execute a job twice. The Java connect timeout
is 5 seconds; the operation timeout is 30 minutes. Cancellation and navigation
interrupt the background task; unfinished result files are removed. Python must
clean up its work after client disconnects and expire unused job IDs.

## Regions

The current UI has no region editor or stored region model, so it sends `[]`.
The Java client accepts a Gson `JsonArray` for future integration. The proposed
polygon format uses coordinates in ORIGINAL video pixels, not resized UI pixels:

```json
[{"id":"zone-1","name":"Region 1","points":[{"x":10,"y":20},{"x":120,"y":20},{"x":120,"y":90}]}]
```

This field is transported, not yet validated or analyzed as geometry.

## Storage and Safety

- Original videos are not copied, converted, edited or loaded entirely into heap.
- A `.part` result is streamed into the user-selected external directory and moved
  to the final filename ONLY after a successful, nonempty video response.
- Python's demo uses an OS temporary directory and deletes it after each request.
  This temporary upload is necessary for this whole-video transport, not a Java
  project cache. The real processor owns its own storage policy.
- This first version accepts only loopback endpoints. It is not an authenticated
  remote service. Do not expose the demo server on the LAN or Internet.
- Project data, originals and the encrypted database remain outside this protocol.
