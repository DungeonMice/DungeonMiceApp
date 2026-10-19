"""Load the owned adapter with isolated portable Python on Linux."""
from pathlib import Path
import runpy
import sys

scripts = Path(__file__).resolve().parent
sys.path.insert(0, str(scripts))
sys.argv[0] = str(scripts / "dungeonmice_api_bridge.py")
runpy.run_path(sys.argv[0], run_name="__main__")
