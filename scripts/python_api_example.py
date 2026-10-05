"""Local protocol demo. Returns the received video unchanged, not an analysis engine."""
import argparse
import json
import re
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

JOBS = {}
LOCK = threading.Lock()
CHUNK = 64 * 1024
MAX_VIDEO = 20 * 1024**3


def process_video(input_path, metadata):
    # Replace with the real processor. Return a path inside the temporary directory
    # or an existing result owned by your application. Region data is in metadata.
    return input_path


def validate_metadata(metadata):
    """Backend-specific validation hook before accepting an expensive upload."""
    pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, status, payload):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def length(self, maximum):
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("Use Content-Length, not chunked requests")
        length = int(self.headers.get("Content-Length", "-1"))
        if not 0 < length <= maximum:
            raise ValueError("Invalid Content-Length")
        return length

    def do_POST(self):
        if self.path != "/api/v1/jobs":
            self.reply(404, {"error": "Unknown endpoint"})
            return
        try:
            self.connection.settimeout(30)
            data = self.rfile.read(self.length(65536))
            metadata = json.loads(data)
            size = metadata.get("sizeBytes")
            if (metadata.get("protocolVersion") != 1 or not isinstance(size, int)
                    or not 0 < size <= MAX_VIDEO or not isinstance(metadata.get("regions"), list)
                    or not isinstance(metadata.get("filename"), str)):
                raise ValueError("Invalid metadata")
            validate_metadata(metadata)
            job_id = uuid.uuid4().hex
            with LOCK:
                now = time.monotonic()
                for key in list(JOBS):
                    if JOBS[key][0] < now - 3600:
                        del JOBS[key]
                if len(JOBS) >= 100:
                    raise ValueError("Too many pending jobs")
                JOBS[job_id] = (now, metadata)
            self.reply(201, {"jobId": job_id})
        except (ValueError, OSError, TypeError, AttributeError) as error:
            self.reply(400, {"error": str(error)})

    def do_PUT(self):
        match = re.fullmatch(r"/api/v1/jobs/([a-f0-9]{32})/video", self.path)
        if not match:
            self.reply(404, {"error": "Unknown endpoint"})
            return
        with LOCK:
            job = JOBS.pop(match.group(1), None)
        if job is None:
            self.reply(404, {"error": "Unknown or already used job"})
            return
        response_started = False
        try:
            self.connection.settimeout(60)
            remaining = self.length(MAX_VIDEO)
            metadata = job[1]
            if remaining != metadata["sizeBytes"]:
                raise ValueError("Video size differs from metadata")
            # Outside the Java project; cleaned up after sending or disconnecting.
            with tempfile.TemporaryDirectory(prefix="dungeonmice-python-") as folder:
                from pathlib import Path
                original = Path(folder) / "input.video"
                with original.open("wb") as output:
                    while remaining:
                        chunk = self.rfile.read(min(CHUNK, remaining))
                        if not chunk:
                            raise ValueError("Incomplete video upload")
                        output.write(chunk)
                        remaining -= len(chunk)
                result = Path(process_video(original, metadata))
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(result.stat().st_size))
                self.send_header("Connection", "close")
                self.end_headers()
                response_started = True
                with result.open("rb") as input_file:
                    while True:
                        chunk = input_file.read(CHUNK)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                self.close_connection = True
        except (ValueError, OSError, TypeError) as error:
            if not response_started:
                self.reply(400, {"error": str(error)})
            else:
                self.close_connection = True


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Python video API: http://127.0.0.1:{server.server_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
