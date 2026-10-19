"""Real H.264/AAC export using FFmpeg even when OpenCV has no VideoWriter encoder."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import cv2
import numpy as np
from dungeonmice_api_bridge import HeadlessVideo


class Mp4EncoderChecks(unittest.TestCase):
    def test_real_h264_audio_clock_frames_and_pixels(self):
        with tempfile.TemporaryDirectory(prefix="dungeonmice-encoder-check-") as temporary:
            result = Path(temporary) / "processed.mp4"
            display = HeadlessVideo(cv2, result); display.fps = 30; display.total = 120
            try:
                with patch.object(cv2, "VideoWriter", side_effect=AssertionError("OpenCV encoder unavailable")):
                    for index in range(120):
                        image = np.full((240, 320, 3), (index, 60, 160), dtype=np.uint8)
                        cv2.putText(image, f"Frame {index + 1}", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, .7, (255, 255, 255), 2)
                        display.imshow("fgmask", image)
                        display.imshow("frame", image)
                    display.destroyAllWindows()
                info = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(result)]))
                video = next(stream for stream in info["streams"] if stream["codec_type"] == "video")
                audio = next(stream for stream in info["streams"] if stream["codec_type"] == "audio")
                self.assertEqual(video["codec_name"], "h264"); self.assertEqual(audio["codec_name"], "aac")
                self.assertEqual((video["width"], video["height"], int(video["nb_frames"])), (320, 240, 120))
                capture = cv2.VideoCapture(str(result))
                try:
                    capture.set(cv2.CAP_PROP_POS_FRAMES, 119)
                    ok, last = capture.read(); self.assertTrue(ok)
                    self.assertLess(np.abs(last[100, 100].astype(float) - (119, 60, 160)).max(), 8)
                finally:
                    capture.release()
            finally:
                display.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
