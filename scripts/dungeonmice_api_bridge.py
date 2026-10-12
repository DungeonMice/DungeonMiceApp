"""External HTTP adapter for DungeonMice. Never edits its source or presets."""
import argparse
import copy
import json
import math
import hmac
import os
import re
from pathlib import Path
import select
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer

sys.dont_write_bytecode = True
import python_api_example as protocol
import frame_sessions

ACTIVE = threading.local()
PROCESS_SLOT = threading.Lock()
WORKERS = set()
WORKER_LOCK = threading.Lock()
STOPPING = threading.Event()
OPTIONS = None
PROGRESS = {}
PROGRESS_LOCK = threading.Lock()


def set_progress(job, message):
    if job:
        with PROGRESS_LOCK:
            PROGRESS[job] = message


def normalize_regions(regions):
    if not isinstance(regions, list) or len(regions) > 32:
        raise ValueError("Maximo 32 regiones por video.")
    result = copy.deepcopy(regions)
    ids = set()
    for region in result:
        if not isinstance(region, dict) or not isinstance(region.get("id"), str) or not region["id"].strip() or len(region["id"]) > 60:
            raise ValueError("Cada region necesita un nombre de 1 a 60 caracteres.")
        key = region["id"].strip().casefold()
        if key in ids:
            raise ValueError("Los nombres de las regiones deben ser diferentes.")
        ids.add(key)
        threshold = region.get("overlap_threshold", 0)
        if not isinstance(threshold, (float, int)) or not math.isfinite(threshold) or not 0 <= threshold <= 1:
            raise ValueError("El solapamiento debe estar entre 0 y 1.")
        region.setdefault("type", "PolygonRegion")
        if region["type"] == "PolygonRegion":
            points = region.get("points", [])
            if not isinstance(points, list) or not 3 <= len(points) <= 128:
                raise ValueError("La region necesita entre 3 y 128 vertices.")
            region["points"] = [[p.get("x"), p.get("y")] if isinstance(p, dict) else p for p in points]
            if any(not isinstance(p, (list, tuple)) or len(p) != 2 or any(isinstance(v, bool) or not isinstance(v, (float, int)) or not math.isfinite(v) or v < 0 for v in p)
                   for p in region["points"]):
                raise ValueError("Polygon coordinates must be finite x/y pairs")
            pairs = region["points"]
            area = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(pairs, pairs[1:] + pairs[:1]))
            if abs(area) < 2:
                raise ValueError("La region no tiene area suficiente.")
    return result


def validate_metadata(metadata):
    if not metadata["regions"]:
        return  # Preserve the legacy manual client's explicit preset fallback.
    regions = normalize_regions(metadata["regions"])
    preset = json.loads(OPTIONS.config.read_text(encoding="utf-8"))
    if preset["labyrinth_type"] == "CrossMaze" and (len(regions) < 2 or any(r["type"] != "PolygonRegion" for r in regions)):
        raise ValueError("CrossMaze requiere al menos dos regiones poligonales guardadas.")
    metadata["regions"] = regions


class HeadlessVideo:
    """Redirect only RunExperiment's display calls to a VideoWriter in its worker."""
    def __init__(self, cv2, destination):
        self.cv2 = cv2
        self.destination = destination
        self.capture = None
        self.writer = None
        self.frames = 0
        self.fps = 0
        self.total = 0

    def __getattr__(self, name):
        return getattr(self.cv2, name)

    def VideoCapture(self, filename):
        self.capture = self.cv2.VideoCapture(filename)
        self.fps = self.capture.get(self.cv2.CAP_PROP_FPS)
        self.total = int(self.capture.get(self.cv2.CAP_PROP_FRAME_COUNT))
        if not self.capture.isOpened() or not math.isfinite(self.fps) or self.fps < 1:
            self.close()
            raise ValueError("DungeonMice could not decode the uploaded video")
        return self.capture

    def namedWindow(self, *args):
        pass

    def resizeWindow(self, *args):
        pass

    def imshow(self, name, frame):
        if name != "frame":
            return
        if self.writer is None:
            height, width = frame.shape[:2]
            if os.name == "nt":
                self.writer = self.cv2.VideoWriter(str(self.destination), self.cv2.CAP_MSMF,
                                                  self.cv2.VideoWriter_fourcc(*"avc1"), self.fps, (width, height))
            else:
                self.writer = self.cv2.VideoWriter(str(self.destination), self.cv2.VideoWriter_fourcc(*"avc1"),
                                                  self.fps, (width, height))
            if not self.writer.isOpened():
                raise ValueError("Could not create H.264 MP4; this environment needs an H.264 encoder")
        self.writer.write(frame)
        self.frames += 1
        if self.frames % 300 == 0:
            print(f"Processed {self.frames}/{self.total} frames", flush=True)

    def waitKey(self, *args):
        return -1

    def destroyAllWindows(self):
        if self.writer is not None:
            self.writer.release()
            self.writer = None

    def close(self):
        self.destroyAllWindows()
        if self.capture is not None:
            self.capture.release()


def run_worker(request_path):
    request_path = Path(request_path).resolve()
    request = json.loads(request_path.read_text(encoding="utf-8"))
    folder = request_path.parent
    sys.path.insert(0, str(Path(request["project"]) / "src"))
    import cv2
    import RunExperiment
    from input_loader import load_experiment

    metadata = request["metadata"]
    config = json.loads(Path(request["config"]).read_text(encoding="utf-8"))
    if metadata.get("regions"):
        config["regions"] = normalize_regions(metadata["regions"])
    else:
        # Legacy manual clients can still request the operator-selected preset.
        config["regions"] = normalize_regions(config["regions"])
    source = Path(request["source"])
    if metadata.get("regions"):
        capture = cv2.VideoCapture(str(source))
        try:
            width, height = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH)), int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
            if not capture.isOpened() or width <= 0 or height <= 0:
                raise ValueError("No se pudo leer la resolucion del video.")
            for region in config["regions"]:
                if region.get("frameWidth", width) != width or region.get("frameHeight", height) != height:
                    raise ValueError("La resolucion cambio. Vuelve a dibujar las regiones.")
                if region["type"] == "PolygonRegion" and any(x >= width or y >= height for x, y in region["points"]):
                    raise ValueError("Hay vertices fuera del video.")
        finally:
            capture.release()
    config["video_path"] = str(source)
    config_path = folder / "experiment-api.json"
    config_path.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
    labyrinth = load_experiment(config_path)
    output = folder / "processed.mp4"
    encoded = folder / "annotated-video.mp4"
    display = HeadlessVideo(cv2, encoded)
    # This binding is private to the disposable subprocess. No OpenCV global or
    # existing Python file is changed; tracking, regions and event logic are reused.
    original_cv2 = RunExperiment.cv2
    RunExperiment.cv2 = display
    started = time.monotonic()
    try:
        results = RunExperiment.process_single_video(str(source), labyrinth)
        if len(results) != 5 or results[0] is None or display.frames == 0:
            raise ValueError("DungeonMice did not produce video results")
        states, trajectory_x, trajectory_y, first_frame, recording_duration = results
        print("Stage: Generando video y resultados", flush=True)
        # Mux a silent AAC clock track without re-encoding frames. VLC's callback
        # input needs a stable MP4 clock for seeking these video-only exports.
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-i", str(encoded),
                        "-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=44100",
                        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
                        "-shortest", "-movflags", "+faststart", str(output)], check=True, timeout=120)
        encoded.unlink()
        name = "api-video"
        labyrinth.process_video(
            all_results={name: states}, all_trajectories={name: (trajectory_x, trajectory_y)},
            all_video_paths={name: str(source)}, all_first_frames={name: first_frame},
            all_start_times={name: labyrinth.start_time}, all_recording_durations={name: recording_duration})
        if not output.is_file() or output.stat().st_size == 0:
            raise ValueError("Empty processed video")
        summary = {
            "frames": display.frames, "fps": display.fps, "trackingPoints": len(trajectory_x),
            "seconds": round(time.monotonic() - started, 3),
            "regions": {key: {"entries": int(state.entries), "totalTime": float(state.total_time)}
                        for key, state in states.items()},
            "reports": sorted(p.name for p in folder.glob("*.xlsx")),
        }
        (folder / "worker-result.json").write_text(json.dumps(summary), encoding="utf-8")
        print(json.dumps(summary), flush=True)
    finally:
        display.close()
        RunExperiment.cv2 = original_cv2


def disconnected(connection):
    readable, _, _ = select.select([connection], [], [], 0)
    if not readable:
        return False
    try:
        return connection.recv(1, socket.MSG_PEEK) == b""
    except (ConnectionError, OSError):
        return True


def stop_worker(worker):
    if worker.poll() is None:
        if os.name == "nt":
            # Kill only this owned worker's tree, including a possible FFmpeg muxer.
            terminated = subprocess.run(["taskkill", "/PID", str(worker.pid), "/T", "/F"],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            if terminated.returncode == 0:
                worker.wait(timeout=5)
                return
        worker.terminate()
        try:
            worker.wait(timeout=5)
        except subprocess.TimeoutExpired:
            worker.kill()
            worker.wait()


def process_video(source, metadata):
    folder = Path(source).parent
    request = {"project": str(OPTIONS.python_project), "config": str(OPTIONS.config),
               "source": str(source), "metadata": metadata}
    request_path = folder / "worker-request.json"
    request_path.write_text(json.dumps(request, ensure_ascii=False), encoding="utf-8")
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUNBUFFERED="1", PYTHONUTF8="1")
    with (folder / "worker.log").open("wb") as log:
        worker = subprocess.Popen([sys.executable, "-B", str(Path(__file__).resolve()), "--worker", str(request_path)],
                                  cwd=str(folder), env=environment, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        with WORKER_LOCK:
            WORKERS.add(worker)
        started = time.monotonic()
        last_progress = started - 1
        set_progress(getattr(ACTIVE, "job", None), "Iniciando analisis en Python...")
        try:
            while worker.poll() is None:
                if disconnected(ACTIVE.connection):
                    raise ValueError("Java disconnected; processing cancelled")
                if time.monotonic() - started > OPTIONS.timeout:
                    raise ValueError("DungeonMice processing timed out")
                if time.monotonic() - last_progress >= 1:
                    with (folder / "worker.log").open("rb") as output:
                        output.seek(max(0, output.seek(0, 2) - 4096))
                        lines = output.read().decode("utf-8", errors="replace")
                    progress = re.findall(r"Processed (\d+)/(\d+) frames|Stage: ([^\r\n]+)", lines)
                    if progress:
                        frames, total, stage = progress[-1]
                        message = stage if stage else f"Procesando: {frames}/{total} frames"
                        set_progress(getattr(ACTIVE, "job", None), message)
                    last_progress = time.monotonic()
                time.sleep(.2)
            if worker.returncode != 0:
                detail = (folder / "worker.log").read_text(encoding="utf-8", errors="replace")[-4000:]
                print(detail, file=sys.stderr, flush=True)
                last_line = next((line.strip() for line in reversed(detail.splitlines()) if line.strip()), "Error desconocido")
                raise ValueError("Python: " + last_line[:500])
            summary = json.loads((folder / "worker-result.json").read_text(encoding="utf-8"))
            print("DungeonMice result: " + json.dumps(summary), flush=True)
            return folder / "processed.mp4"
        finally:
            stop_worker(worker)
            with WORKER_LOCK:
                WORKERS.discard(worker)


class Handler(protocol.Handler):
    def reply(self, status, payload):
        if status == 201 and "jobId" in payload:
            with PROGRESS_LOCK:
                if len(PROGRESS) >= 100:
                    PROGRESS.pop(next(iter(PROGRESS)))
                PROGRESS[payload["jobId"]] = "Esperando video..."
        super().reply(status, payload)

    def do_GET(self):
        if frame_sessions.handle(self, OPTIONS, normalize_regions, PROCESS_SLOT, STOPPING):
            return
        match = re.fullmatch(r"/api/v1/jobs/([a-f0-9]{32})/status", self.path)
        with PROGRESS_LOCK:
            message = PROGRESS.get(match.group(1)) if match else None
        self.reply(200, {"message": message}) if message else self.reply(404, {"error": "Unknown job"})

    def do_POST(self):
        if frame_sessions.handle(self, OPTIONS, normalize_regions, PROCESS_SLOT, STOPPING):
            return
        if self.path == "/api/v1/shutdown":
            expected = os.environ.get("DUNGEONMICE_SHUTDOWN_TOKEN", "")
            supplied = self.headers.get("X-DungeonMice-Token", "")
            if not expected or not hmac.compare_digest(expected, supplied):
                self.reply(403, {"error": "Forbidden"})
                return
            STOPPING.set()
            self.reply(200, {"status": "stopping"})
            threading.Thread(target=self.server.shutdown, name="dungeonmice-service-stop", daemon=True).start()
            return
        if STOPPING.is_set():
            self.reply(503, {"error": "Service is stopping"})
            return
        super().do_POST()

    def do_PUT(self):
        if STOPPING.is_set() or not PROCESS_SLOT.acquire(blocking=False):
            self.reply(503, {"error": "Another video is being processed; try again later"})
            return
        ACTIVE.connection = self.connection
        match = re.fullmatch(r"/api/v1/jobs/([a-f0-9]{32})/video", self.path)
        ACTIVE.job = match.group(1) if match else None
        set_progress(ACTIVE.job, "Recibiendo video...")
        try:
            super().do_PUT()
        finally:
            ACTIVE.connection = None
            with PROGRESS_LOCK:
                PROGRESS.pop(ACTIVE.job, None)
            ACTIVE.job = None
            PROCESS_SLOT.release()

    def do_DELETE(self):
        if not frame_sessions.handle(self, OPTIONS, normalize_regions, PROCESS_SLOT, STOPPING):
            self.reply(404, {"error": "Unknown endpoint"})


def main():
    global OPTIONS
    parser = argparse.ArgumentParser()
    parser.add_argument("--python-project", type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--timeout", type=float, default=1800)
    parser.add_argument("--worker", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        run_worker(args.worker)
        return
    if not args.python_project:
        parser.error("--python-project is required")
    args.python_project = args.python_project.resolve(strict=True)
    args.config = (args.config or args.python_project / "src" / "CrossMaze_control_s1.json").resolve(strict=True)
    if not (args.python_project / "src" / "RunExperiment.py").is_file():
        parser.error("Missing src/RunExperiment.py")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be positive")
    import cv2
    import numpy
    import openpyxl
    if shutil.which("ffmpeg") is None:
        parser.error("FFmpeg must be available on PATH for MP4/AAC muxing")
    print(f"Dependencies: OpenCV {cv2.__version__}, NumPy {numpy.__version__}, openpyxl {openpyxl.__version__}", flush=True)
    OPTIONS = args
    threading.Thread(target=frame_sessions.expire, args=(PROCESS_SLOT, STOPPING), daemon=True).start()
    protocol.process_video = process_video
    protocol.validate_metadata = validate_metadata
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = False
    print(f"DungeonMice API: http://127.0.0.1:{server.server_port}", flush=True)
    print(f"Fallback region preset: {args.config}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        STOPPING.set()
        frame_sessions.close_all(PROCESS_SLOT)
        with WORKER_LOCK:
            remaining = list(WORKERS)
        for worker in remaining:
            stop_worker(worker)
        server.server_close()


if __name__ == "__main__":
    main()
