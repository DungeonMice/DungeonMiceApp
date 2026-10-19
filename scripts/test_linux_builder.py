"""Host-side tests for relocatable tar assembly and desktop command quoting."""
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
import hashlib
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load("linux_builder", SCRIPTS / "build-linux.py")
setup = load("linux_setup", SCRIPTS / "linux/setup-linux.py")


class LinuxBuilderChecks(unittest.TestCase):
    def test_runtime_permissions_and_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = root / "runtime.tar.gz"
            with tarfile.open(runtime, "w:gz") as archive:
                binary = tarfile.TarInfo("python/bin/python3.14")
                binary.mode, binary.size = 0o755, 4
                archive.addfile(binary, io.BytesIO(b"ELF!"))
                link = tarfile.TarInfo("python/bin/python3")
                link.type, link.linkname = tarfile.SYMTYPE, "python3.14"
                archive.addfile(link)
            output = root / "bundle.tar.gz"
            with tarfile.open(output, "w:gz") as archive:
                builder.Bundle(archive).runtime(runtime, "backend/runtime/python")
            with tarfile.open(output) as archive:
                prefix = "DungeonMice/backend/runtime/python/bin/"
                self.assertEqual(archive.getmember(prefix + "python3.14").mode, 0o755)
                self.assertTrue(archive.getmember(prefix + "python3").issym())
                self.assertEqual(archive.getmember(prefix + "python3").linkname, "python3.14")

    def test_runtime_link_cannot_escape(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "bad.tar.gz"
            with tarfile.open(source, "w:gz") as archive:
                item = tarfile.TarInfo("python/bin/escape")
                item.type, item.linkname = tarfile.SYMTYPE, "../../../outside"
                archive.addfile(item)
            with tarfile.open(root / "result.tar.gz", "w:gz") as archive:
                with self.assertRaises(ValueError):
                    builder.Bundle(archive).runtime(source, "runtime")

    def test_sources_are_not_reformatted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "engine.py"
            original = b"# unchanged engine\r\nvalue = 1\r\n"
            source.write_bytes(original)
            output = root / "result.tar.gz"
            with tarfile.open(output, "w:gz") as archive:
                builder.Bundle(archive).file("backend/engine/src/engine.py", source)
            with tarfile.open(output) as archive:
                self.assertEqual(archive.extractfile("DungeonMice/backend/engine/src/engine.py").read(), original)

    def test_bad_cache_is_rejected_without_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "runtime.tar.gz").write_bytes(b"incorrect")
            artifact = dict(file="runtime.tar.gz", url="https://invalid.example", sha256=hashlib.sha256(b"valid").hexdigest())
            with self.assertRaises(ValueError):
                builder.download(artifact, root)

    def test_desktop_escaping(self):
        path = '/home/user/with space/"quoted"/$dollar/`literal`/back\\slash/50%/DungeonMice'
        value = setup.desktop_escape(path)
        self.assertEqual(value, r'/home/user/with space/\\"quoted\\"/\\$dollar/\\`literal\\`/back\\\\slash/50%%/DungeonMice')
        with self.assertRaises(ValueError):
            setup.desktop_escape("/tmp/a\nb")

    def test_isolated_bootstrap_finds_only_owned_sibling(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "python_bootstrap.py").write_bytes((SCRIPTS / "python_bootstrap.py").read_bytes())
            (root / "helper.py").write_text("VALUE = 'PASS isolated adapter imports'\n")
            (root / "dungeonmice_api_bridge.py").write_text("import helper, sys\nassert sys.argv[1:] == ['--probe', 'x y']\nprint(helper.VALUE)\n")
            result = subprocess.run([sys.executable, "-I", "-B", str(root / "python_bootstrap.py"), "--probe", "x y"], cwd=root.parent, capture_output=True, text=True, check=True)
            self.assertIn("PASS isolated adapter imports", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
