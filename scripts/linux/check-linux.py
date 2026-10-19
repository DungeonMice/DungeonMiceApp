"""Linux-side smoke check of the extracted portable package."""
import argparse
import ctypes
import ctypes.util
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ui", action="store_true", help="Abrir y cerrar la UI con datos temporales para probar su conexion Python")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    if sys.platform != "linux" or platform.machine() != "x86_64":
        parser.error("Este paquete requiere Linux x86_64")
    backend = root / "backend"
    assert Path(sys.executable).resolve().is_relative_to(backend / "runtime/python")
    assert sys.flags.isolated and sys.dont_write_bytecode
    manifest = json.loads((backend / "bundle-manifest.json").read_text())
    libc, version = platform.libc_ver()
    assert libc == "glibc" and tuple(map(int, version.split(".")[:2])) >= (2, 28), "Se requiere glibc 2.28 o posterior"
    assert sys.version.split()[0] == manifest["pythonVersion"]
    sys.path.insert(0, str(backend / "scripts"))
    sys.path.insert(0, str(backend / "engine/src"))
    import cv2
    import numpy
    import openpyxl
    import RunExperiment_per_frame
    import dungeonmice_api_bridge
    for module in (cv2, numpy, openpyxl, RunExperiment_per_frame, dungeonmice_api_bridge):
        assert Path(module.__file__).resolve().is_relative_to(backend)
    assert hasattr(cv2, "TrackerCSRT_create")
    assert numpy.zeros((2, 2)).sum() == 0
    openpyxl.Workbook().save(io.BytesIO())
    for item in manifest["engineFiles"]:
        file = backend / item["path"]
        assert hashlib.sha256(file.read_bytes()).hexdigest() == item["sha256"], file
    vlc = ctypes.util.find_library("vlc")
    assert vlc, "Instala VLC: ./install-dependencies.sh"
    ctypes.CDLL(vlc)
    subprocess.run([str(root / "runtime/bin/java"), "-version"], check=True)
    print("PASS Java, Python, OpenCV, tracker, VLC y motor original disponibles", flush=True)
    if args.ui:
        with tempfile.TemporaryDirectory(prefix="dungeonmice-linux-smoke-") as temporary:
            env = dict(os.environ, XDG_DATA_HOME=temporary, PYTHONHOME="/nonexistent/dungeonmice-python", PYTHONPATH="/nonexistent/dungeonmice-python")
            result = subprocess.run([str(root / "DungeonMice"), "--python-startup-check"], cwd=temporary, env=env, capture_output=True, text=True, timeout=45)
            print(result.stdout, end="")
            if result.returncode or "PASS App automatic Python startup" not in result.stdout or "PASS App automatic Python shutdown" not in result.stdout:
                raise RuntimeError("Fallo la prueba de UI y Python: " + result.stderr)
        print("PASS UI y Python inician y terminan con datos temporales", flush=True)


if __name__ == "__main__":
    main()
