"""Bounded cache, EOF, authentication and release without native GUI dependencies."""
import collections
import io
import os
import struct
import tempfile
from pathlib import Path
import zipfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
import frame_sessions as frames


class FrameSessionChecks(unittest.TestCase):
    def session(self):
        session = frames.Session.__new__(frames.Session)
        session.id = "a" * 32
        session.count = session.bytes = 0
        session.completed = False
        session.cache = collections.OrderedDict()
        session.analyzer = Mock()
        session.analyzer._cache = []
        session.analyzer.release = Mock()
        state = SimpleNamespace(inside=False, entries=0, total_time=0)
        def next_frame():
            index = session.count + 1
            data = SimpleNamespace(index=index, time=index / 30, image=object(),
                                   position=None, region_states={"north": state}, recording_active=True)
            session.analyzer._cache.append(data)
            return data
        session.analyzer.next.side_effect = next_frame
        session.cv2 = Mock(IMWRITE_JPEG_QUALITY=1)
        session.cv2.imencode.return_value = (True, SimpleNamespace(tobytes=lambda: b"jpeg" * 100))
        return session

    def tearDown(self):
        frames.SESSION = None

    def test_compressed_cache_is_count_bounded(self):
        session = self.session()
        for _ in range(140):
            packet = session.next()
            self.assertFalse(session.analyzer._cache)
        self.assertEqual(session.count, 140)
        self.assertEqual(len(session.cache), 120)
        self.assertEqual(next(iter(session.cache)), 21)
        self.assertEqual(session.bytes, sum(map(len, session.cache.values())))
        self.assertGreater(struct.unpack(">I", packet[:4])[0], 0)

    def test_compressed_cache_is_byte_bounded(self):
        session = self.session()
        with patch.object(frames, "MAX_BYTES", 1500):
            for _ in range(10):
                session.next()
                self.assertLessEqual(session.bytes, 1500)
        self.assertLess(len(session.cache), 10)

    def test_oversized_frame_rejected(self):
        session = self.session()
        with patch.object(frames, "MAX_BYTES", 10):
            with self.assertRaises(ValueError):
                session.next()
        self.assertFalse(session.cache)
        self.assertFalse(session.analyzer._cache)

    def test_eof_returns_no_packet(self):
        session = self.session()
        session.analyzer.next.side_effect = None
        session.analyzer.next.return_value = None
        self.assertIsNone(session.next())
        self.assertEqual(session.count, 0)

    def test_reports_require_completion(self):
        with self.assertRaisesRegex(ValueError, "finalizar"):
            self.session().reports()

    def test_native_workbook_is_preserved_without_writes_next_to_source(self):
        from openpyxl import Workbook
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "original.avi"; source.write_bytes(b"unchanged")
            session = self.session(); session.completed = True; session.report_packet = None
            session.report_workspace = None; session.source = source; session.kind = "MorrisPool"; session.first_frame = object()
            session.analyzer.fps = 30; session.count = 60
            def write_results(**args):
                target = Path(next(iter(args["all_video_paths"].values()))).parent
                self.assertNotEqual(target, source.parent)
                book = Workbook(); book.active.append(["Native metric", 3.5]); book.save(target / "results.xlsx")
            writer = Mock(side_effect=write_results)
            session.analyzer._labyrinth = SimpleNamespace(trajectory_x=[1], trajectory_y=[2], start_time=0, process_video=writer)
            packet = session.reports()
            self.assertEqual(packet, session.reports()); writer.assert_called_once()
            with zipfile.ZipFile(io.BytesIO(packet)) as archive:
                self.assertIn("results.xlsx", archive.namelist())
                import json
                data = json.loads(archive.read("report.json"))
                self.assertEqual(data["sheets"][0]["rows"][0], ["Native metric", "3.5"])
            owned = Path(session.report_workspace.name); session.close()
            self.assertFalse(owned.exists()); self.assertEqual(source.read_bytes(), b"unchanged")
            self.assertEqual(list(root.iterdir()), [source])

    def test_unauthenticated_path_api_does_not_open_source(self):
        handler = Mock(path="/api/v1/frame-sessions", headers={}, command="POST")
        normalizer = Mock()
        with patch.dict(os.environ, DUNGEONMICE_SHUTDOWN_TOKEN="secret"):
            self.assertTrue(frames.handle(handler, Mock(), normalizer, Mock(), Mock()))
        self.assertEqual(handler.reply.call_args[0][0], 403)
        normalizer.assert_not_called()

    def test_release_discards_frames_and_analysis_slot(self):
        session = self.session()
        session.next()
        frames.SESSION = session
        slot = Mock()
        frames.close_all(slot)
        session.analyzer.release.assert_called_once()
        slot.release.assert_called_once()
        self.assertIsNone(frames.SESSION)
        self.assertFalse(session.cache)
        frames.close_all(slot)
        slot.release.assert_called_once()

    def test_release_error_still_frees_slot_and_cache(self):
        session = self.session()
        session.next()
        session.analyzer.release.side_effect = RuntimeError("release failed")
        frames.SESSION = session
        slot = Mock()
        with self.assertRaises(RuntimeError):
            frames.close_all(slot)
        self.assertIsNone(frames.SESSION)
        self.assertFalse(session.cache)
        slot.release.assert_called_once()

    def test_views_share_index_and_cache_entry(self):
        import json
        session = self.session()
        session.views = {"detection": object(), "trajectory": object()}
        packet = session.next()
        length = struct.unpack(">I", packet[:4])[0]
        metadata = json.loads(packet[4:4 + length])
        self.assertEqual(metadata["index"], 1)
        self.assertEqual([view["id"] for view in metadata["images"]], ["video", "detection", "trajectory"])
        self.assertEqual(sum(view["length"] for view in metadata["images"]), len(packet) - 4 - length)
        self.assertEqual(len(session.cache), 1)
        self.assertEqual(session.analyzer.next.call_count, 1)


if __name__ == "__main__":
    unittest.main()
