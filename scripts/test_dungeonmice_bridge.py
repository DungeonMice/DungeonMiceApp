import unittest
from unittest.mock import Mock, patch
from pathlib import Path
import socket
import tempfile
import json
from types import SimpleNamespace
import dungeonmice_api_bridge as bridge


class BridgeChecks(unittest.TestCase):
    def test_ffmpeg_is_only_required_for_mp4_export(self):
        with patch.object(bridge.shutil, "which", return_value=None):
            with self.assertRaisesRegex(ValueError, "frames no lo necesita"):
                bridge.require_mp4_encoder()
        with patch.object(bridge.shutil, "which", return_value="ffmpeg"):
            bridge.require_mp4_encoder()

    def test_polygon_translation_does_not_modify_original(self):
        regions = [{"id": "este", "points": [{"x": 1, "y": 2}, {"x": 4, "y": 2}, {"x": 4, "y": 5}]}]
        native = bridge.normalize_regions(regions)
        self.assertEqual(native[0]["type"], "PolygonRegion")
        self.assertEqual(native[0]["points"], [[1, 2], [4, 2], [4, 5]])
        self.assertIsInstance(regions[0]["points"][0], dict)

    def test_invalid_polygon_is_rejected(self):
        with self.assertRaises(ValueError):
            bridge.normalize_regions([{"id": "bad", "points": [[1, 2]]}])
        with self.assertRaises(ValueError):
            bridge.normalize_regions([{"id": "bad", "points": [[float("nan"), 2], [4, 2], [4, 5]]}])

    def test_native_circle_is_preserved(self):
        regions = [{"type": "CircleRegion", "id": "pool", "center": [20, 30], "radius": 15}]
        self.assertEqual(bridge.normalize_regions(regions), regions)

    def test_names_threshold_and_area_validated(self):
        triangle = {"id": "este", "points": [[1, 2], [4, 2], [4, 5]]}
        with self.assertRaisesRegex(ValueError, "diferentes"):
            bridge.normalize_regions([triangle, dict(triangle, id="ESTE")])
        with self.assertRaisesRegex(ValueError, "solapamiento"):
            bridge.normalize_regions([dict(triangle, overlap_threshold=float("nan"))])
        with self.assertRaisesRegex(ValueError, "area"):
            bridge.normalize_regions([dict(triangle, points=[[1, 1], [2, 2], [3, 3]])])
        with self.assertRaises(ValueError):
            bridge.normalize_regions([dict(triangle, points=[{}, [4, 2], [4, 5]])])

    def test_crossmaze_validation_before_upload(self):
        old_options = bridge.OPTIONS
        try:
            with tempfile.TemporaryDirectory() as folder:
                config = Path(folder) / "preset.json"
                config.write_text(json.dumps({"labyrinth_type": "CrossMaze"}))
                bridge.OPTIONS = SimpleNamespace(config=config)
                triangle = {"id": "norte", "points": [[1, 2], [4, 2], [4, 5]]}
                with self.assertRaisesRegex(ValueError, "dos regiones"):
                    bridge.validate_metadata({"regions": [triangle]})
                metadata = {"regions": [triangle, dict(triangle, id="sur")]}
                bridge.validate_metadata(metadata)
                self.assertEqual(metadata["regions"][0]["type"], "PolygonRegion")
                bridge.validate_metadata({"regions": []})
        finally:
            bridge.OPTIONS = old_options

    def test_display_adapter_encodes_only_annotated_frame_without_opencv_writer(self):
        cv2 = Mock()
        cv2.VideoCapture.return_value.isOpened.return_value = True
        cv2.VideoCapture.return_value.get.return_value = 30
        display = bridge.HeadlessVideo(cv2, Path("processed.mp4"))
        display.VideoCapture("source.mov")
        frame = Mock(); frame.shape = (720, 1280, 3); frame.dtype.name = "uint8"; frame.tobytes.return_value = b"annotated-frame"
        process = Mock(); process.wait.return_value = 0
        with patch.object(bridge.subprocess, "Popen", return_value=process) as start, patch.object(bridge.shutil, "which", return_value="ffmpeg"):
            display.imshow("fgmask", frame)
            start.assert_not_called()
            display.imshow("frame", frame)
            command = start.call_args.args[0]
            self.assertIn("libx264", command); self.assertIn("aac", command)
            self.assertIn("pipe:0", command)
        cv2.VideoWriter.assert_not_called()
        process.stdin.write.assert_called_once_with(b"annotated-frame")
        self.assertEqual(display.frames, 1)
        self.assertEqual(display.waitKey(1), -1)
        display.namedWindow("frame"); display.resizeWindow("frame", 1280, 720)
        cv2.namedWindow.assert_not_called(); cv2.resizeWindow.assert_not_called()
        display.close()
        process.stdin.close.assert_called_once()
        process.wait.assert_called_once_with(timeout=120)
        self.assertIsNone(display.encoder_log)
        cv2.VideoCapture.return_value.release.assert_called_once()

    def test_ffmpeg_encoder_error_is_reported_and_released(self):
        display = bridge.HeadlessVideo(Mock(), Path("processed.mp4")); display.fps = 30
        frame = Mock(); frame.shape = (2, 2, 3); frame.dtype.name = "uint8"; frame.tobytes.return_value = bytes(12)
        process = Mock(); process.wait.return_value = 1
        with patch.object(bridge.subprocess, "Popen", return_value=process), patch.object(bridge.shutil, "which", return_value="ffmpeg"):
            display.imshow("frame", frame)
        display.encoder_log.write(b"Unknown encoder libx264")
        with self.assertRaisesRegex(ValueError, "Unknown encoder libx264"):
            display.close()
        self.assertIsNone(display.writer); self.assertIsNone(display.encoder_log)
        display.close()
        process.wait.assert_called_once()

    def test_morris_geometry_validation(self):
        from experiment_config import select_config, validate_frame_regions
        region = dict(type="CircularFractionRegion", id="plataforma", center=[100, 100], radius=80,
                      angle_start=270, fraction=.25, overlap_threshold=.75, frameWidth=300, frameHeight=220)
        self.assertEqual(bridge.normalize_regions([region]), [region])
        validate_frame_regions([region], 300, 220)
        with self.assertRaises(ValueError):
            bridge.normalize_regions([dict(region, radius=float("nan"))])
        with self.assertRaises(ValueError):
            bridge.normalize_regions([dict(region, fraction=.25, angle_end=360)])
        with self.assertRaises(ValueError):
            validate_frame_regions([dict(region, radius=150)], 300, 220)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "preset.json"
            path.write_text(json.dumps(dict(labyrinth_type="MorrisPool")))
            options = SimpleNamespace(config=path)
            self.assertEqual(select_config(options, [region])["labyrinth_type"], "MorrisPool")
            with self.assertRaisesRegex(ValueError, "90 grados"):
                select_config(options, [dict(region, fraction=.5)])

    def test_disconnect_terminates_owned_worker(self):
        connection, peer = socket.socketpair()
        peer.close()
        old_options = bridge.OPTIONS
        bridge.OPTIONS = SimpleNamespace(python_project=Path("project"), config=Path("preset.json"), timeout=10)
        bridge.ACTIVE.connection = connection
        worker = Mock(pid=13579); worker.poll.return_value = None
        try:
            with tempfile.TemporaryDirectory() as folder, patch.object(bridge.subprocess, "Popen", return_value=worker), patch.object(bridge.subprocess, "run", return_value=SimpleNamespace(returncode=0)) as terminate:
                with self.assertRaisesRegex(ValueError, "disconnected"):
                    bridge.process_video(Path(folder) / "input.video", {"regions": []})
                if bridge.os.name == "nt":
                    self.assertEqual(terminate.call_args.args[0], ["taskkill", "/PID", "13579", "/T", "/F"])
                else:
                    worker.terminate.assert_called_once()
                worker.wait.assert_called()
                self.assertNotIn(worker, bridge.WORKERS)
        finally:
            bridge.OPTIONS = old_options
            bridge.ACTIVE.connection = None
            connection.close()


if __name__ == "__main__":
    unittest.main()
