"""Verify vendored imports and engine hashes without writing to the installation."""
import hashlib
import io
import json
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve(strict=True)
assert Path(sys.executable).resolve().is_relative_to(root / "runtime" / "python")
assert sys.flags.isolated and sys.dont_write_bytecode
sys.path.insert(0, str(root / "engine" / "src"))
import cv2
import numpy
import openpyxl
import RunExperiment_per_frame
import input_loader
import dungeonmice_api_bridge

for module in (cv2, numpy, openpyxl, RunExperiment_per_frame, input_loader, dungeonmice_api_bridge):
    assert Path(module.__file__).resolve().is_relative_to(root), (module.__name__, module.__file__)
assert hasattr(cv2, "TrackerCSRT_create")
assert numpy.zeros((2, 2), dtype=numpy.uint8).sum() == 0
workbook = openpyxl.Workbook()
workbook.save(io.BytesIO())
manifest = json.loads((root / "bundle-manifest.json").read_text(encoding="utf-8-sig"))
assert sys.version.split()[0] == manifest["pythonVersion"]
for item in manifest["engineFiles"]:
    path = (root / item["path"]).resolve(strict=True)
    assert path.is_relative_to(root / "engine")
    assert hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"]
print("PASS Portable Python imports OpenCV/NumPy/OpenPyXL and the unchanged native engine entirely from the bundle")
