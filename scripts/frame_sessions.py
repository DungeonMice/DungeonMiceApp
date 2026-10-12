"""Demand-driven frame adapter. No changes or video outputs in the Python project."""
import collections
import hmac
import json
import math
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import threading
import time

LOCK = threading.RLock()
SESSION = None
MAX_BYTES = 32 * 1024 * 1024
IDLE_SECONDS = 300


class Session:
    def __init__(self, identifier, request, options, normalize):
        self.id = identifier
        self.last = time.monotonic()
        self.cache = collections.OrderedDict()
        self.bytes = 0
        self.count = 0
        sys.path.insert(0, str(options.python_project / "src"))
        import cv2
        from input_loader import load_experiment
        from RunExperiment_per_frame import VideoAnalyzer
        source = Path(request["sourcePath"]).resolve(strict=True)
        if not source.is_file() or source.suffix.lower() not in {".mp4", ".mov", ".avi", ".mkv", ".m4v"}:
            raise ValueError("Video original no disponible")
        regions = normalize(request["regions"])
        if len(regions) < 2:
            raise ValueError("Guarda al menos dos regiones")
        capture = cv2.VideoCapture(str(source))
        try:
            self.width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
            self.height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
            if not capture.isOpened() or self.width <= 0 or self.height <= 0:
                raise ValueError("No se pudo abrir el video original")
            for region in regions:
                if region.get("frameWidth") != self.width or region.get("frameHeight") != self.height:
                    raise ValueError("La resolucion cambio. Vuelve a dibujar las regiones")
                if any(x >= self.width or y >= self.height for x, y in region["points"]):
                    raise ValueError("Vertices fuera del video")
        finally:
            capture.release()
        config = json.loads(options.config.read_text(encoding="utf-8"))
        config.update(video_path=str(source), regions=regions)
        with tempfile.TemporaryDirectory(prefix="dungeonmice-frame-config-") as temp:
            path = Path(temp) / "experiment.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            labyrinth = load_experiment(path)
            self.analyzer = VideoAnalyzer(labyrinth)
        if self.analyzer.fps <= 0 or self.analyzer.total_frames <= 0:
            self.analyzer.release()
            raise ValueError("FPS o duracion invalidos")
        self.cv2 = cv2

    def info(self):
        return dict(sessionId=self.id, width=self.width, height=self.height,
                    fps=self.analyzer.fps, total=self.analyzer.total_frames,
                    processed=self.count, firstCached=next(iter(self.cache), 0), cachedBytes=self.bytes)

    def next(self):
        data = self.analyzer.next()
        if data is None:
            return None
        # The pulled engine otherwise retains every full-resolution BGR image.
        # Keep tracker state intact, but own only a bounded compressed preview window.
        self.analyzer._cache.clear()
        ok, encoded = self.cv2.imencode(".jpg", data.image, [self.cv2.IMWRITE_JPEG_QUALITY, 88])
        if not ok:
            raise ValueError("No se pudo codificar el frame")
        states = {name: dict(inside=bool(state.inside), entries=int(state.entries),
                            totalTime=float(state.total_time)) for name, state in data.region_states.items()}
        position = None if data.position is None else [int(value) for value in data.position]
        header = json.dumps(dict(index=data.index, time=float(data.time), position=position,
                                regions=states, recordingActive=bool(data.recording_active))).encode("utf-8")
        packet = struct.pack(">I", len(header)) + header + encoded.tobytes()
        if len(packet) > MAX_BYTES:
            raise ValueError("Frame demasiado grande")
        self.count = data.index
        self.cache[data.index] = packet
        self.bytes += len(packet)
        while len(self.cache) > 120 or self.bytes > MAX_BYTES:
            _, old = self.cache.popitem(last=False)
            self.bytes -= len(old)
        return packet

    def close(self):
        try:
            self.analyzer.release()
        finally:
            self.cache.clear()
            self.bytes = 0


def close_all(slot):
    global SESSION
    with LOCK:
        if SESSION is not None:
            try:
                SESSION.close()
            finally:
                SESSION = None
                slot.release()


def expire(slot, stopping):
    while not stopping.wait(15):
        with LOCK:
            if SESSION and time.monotonic() - SESSION.last > IDLE_SECONDS:
                close_all(slot)


def handle(handler, options, normalize, slot, stopping):
    global SESSION
    if not handler.path.startswith("/api/v1/frame-sessions"):
        return False
    token = os.environ.get("DUNGEONMICE_SHUTDOWN_TOKEN", "")
    if not token or not hmac.compare_digest(token, handler.headers.get("X-DungeonMice-Token", "")):
        handler.reply(403, {"error": "Frame API requires the local application token"})
        return True
    match = re.fullmatch(r"/api/v1/frame-sessions/([a-f0-9]{32})(?:/(next|[0-9]+))?", handler.path)
    try:
        handler.connection.settimeout(30)
        with LOCK:
            if stopping.is_set():
                handler.reply(503, {"error": "Service stopping"})
            elif handler.command == "POST" and handler.path == "/api/v1/frame-sessions":
                handler.connection.settimeout(30)
                raw = handler.rfile.read(handler.length(65536))
                request = json.loads(raw)
                identifier = request.get("sessionId", "")
                if not isinstance(identifier, str) or not re.fullmatch(r"[a-f0-9]{32}", identifier):
                    raise ValueError("Invalid session ID")
                if SESSION is not None or not slot.acquire(blocking=False):
                    handler.reply(409, {"error": "Ya hay un analisis activo"})
                else:
                    try:
                        SESSION = Session(identifier, request, options, normalize)
                    except Exception:
                        slot.release()
                        raise
                    handler.reply(201, SESSION.info())
            elif not match or SESSION is None or match.group(1) != SESSION.id:
                handler.reply(410, {"error": "Sesion no disponible"})
            elif handler.command == "DELETE" and match.group(2) is None:
                close_all(slot)
                handler.reply(200, {"closed": True})
            elif handler.command == "GET":
                SESSION.last = time.monotonic()
                action = match.group(2)
                if action is None:
                    handler.reply(200, SESSION.info())
                else:
                    packet = SESSION.next() if action == "next" else SESSION.cache.get(int(action))
                    if packet is None:
                        if action == "next":
                            handler.send_response(204)
                            handler.send_header("Connection", "close")
                            handler.end_headers()
                            handler.close_connection = True
                        else:
                            handler.reply(410, {"error": "Frame fuera de la cache disponible"})
                    else:
                        length = struct.unpack(">I", packet[:4])[0]
                        metadata = json.loads(packet[4:4 + length])
                        metadata["firstCached"] = next(iter(SESSION.cache))
                        header = json.dumps(metadata).encode("utf-8")
                        packet = struct.pack(">I", len(header)) + header + packet[4 + length:]
                        handler.send_response(200)
                        handler.send_header("Content-Type", "application/vnd.dungeonmice.frame")
                        handler.send_header("Content-Length", str(len(packet)))
                        handler.send_header("Connection", "close")
                        handler.end_headers()
                        handler.wfile.write(packet)
                        handler.close_connection = True
            else:
                handler.reply(405, {"error": "Method not allowed"})
    except (BrokenPipeError, ConnectionResetError):
        close_all(slot)
    except Exception as error:
        handler.reply(422, {"error": str(error)[:600]})
    return True
