"""Host-side tests for relocatable tar assembly and desktop command quoting."""
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock
import json
import shutil
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
installer = load("linux_installer", SCRIPTS / "build-linux-installer.py")


class LinuxBuilderChecks(unittest.TestCase):
    def test_self_extracting_payload_offset_and_checksum(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"\x1f\x8btest\nbinary\x00payload\n"
            archive = root / "test.tar.gz"
            archive.write_bytes(payload)
            target = root / "install.run"
            installer.write_installer(archive, SCRIPTS / "linux/installer.run.sh", target)
            content = target.read_bytes()
            header, stored = content.split(b"__DUNGEONMICE_PAYLOAD_BELOW__\n", 1)
            self.assertEqual(stored, payload)
            self.assertIn(hashlib.sha256(payload).hexdigest().encode(), header)
            marker = b"tail -n +" + str(header.count(b"\n") + 2).encode() + b' "$0"'
            self.assertIn(marker, header)

    def bundle_fixture(self, root, version="old"):
        root.mkdir()
        for name in setup.PAYLOAD:
            if name in ("app", "backend", "runtime"):
                (root / name).mkdir()
            else:
                (root / name).write_text("fixture")
        (root / "bundle-manifest.json").write_text(json.dumps(dict(platform="linux-x86_64")))
        (root / "app/version").write_text(version)
        return root

    def test_single_installer_dependencies_copy_and_menu(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.bundle_fixture(root / "extracted")
            (source / "personal-video.avi").write_bytes(b"not part of the program")
            base = root / "user-data"
            database = base / "DungeonMice/data/database.enc"
            database.parent.mkdir(parents=True)
            database.write_bytes(b"user data")
            with mock.patch.object(setup.sys, "platform", "linux"), mock.patch.object(setup.sys, "argv", ["setup-linux.py", "--install"]), mock.patch.object(setup, "__file__", str(source / "setup-linux.py")), mock.patch.object(setup.os, "geteuid", return_value=1000, create=True), mock.patch.dict(setup.os.environ, {"XDG_DATA_HOME": str(base)}), mock.patch.object(setup.subprocess, "run") as dependencies:
                setup.main()
                dependencies.assert_called_once_with([str(source / "install-dependencies.sh")], check=True)
            destination = base / "dungeon-mice-app"
            self.assertTrue((destination / "DungeonMice").is_file())
            self.assertFalse((destination / "personal-video.avi").exists())
            shortcut = (base / "applications/dungeon-mice.desktop").read_text()
            self.assertIn(setup.desktop_escape(str(destination / "DungeonMice")), shortcut)
            shutil.rmtree(source)
            self.assertTrue((destination / "app/version").is_file())
            self.assertEqual(database.read_bytes(), b"user data")

    def test_update_and_copy_failure_preserve_previous_program(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.bundle_fixture(root / "source")
            destination = root / "installed/dungeon-mice-app"
            setup.install_bundle(source, destination)
            (source / "app/version").write_text("new")
            with mock.patch.object(setup.shutil, "copy2", side_effect=OSError("simulated copy failure")):
                with self.assertRaises(OSError):
                    setup.install_bundle(source, destination)
            self.assertEqual((destination / "app/version").read_text(), "old")
            setup.install_bundle(source, destination)
            self.assertEqual((destination / "app/version").read_text(), "new")
            self.assertEqual(list(destination.parent.glob(".dungeon-mice-install-*")), [])

    def test_uninstall_removes_only_owned_program_and_menu(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            installed = self.bundle_fixture(base / "dungeon-mice-app")
            shortcut = base / "applications/dungeon-mice.desktop"
            shortcut.parent.mkdir(); shortcut.write_text("X-DungeonMice-Managed=true")
            data = base / "DungeonMice/data/database.enc"
            data.parent.mkdir(parents=True); data.write_bytes(b"preserve")
            reports = base / "DungeonMice/events/report.xlsx"
            reports.parent.mkdir(); reports.write_bytes(b"report")
            setup.uninstall_bundle(base)
            self.assertFalse(installed.exists()); self.assertFalse(shortcut.exists())
            self.assertEqual(data.read_bytes(), b"preserve"); self.assertEqual(reports.read_bytes(), b"report")
            setup.uninstall_bundle(base)

    def test_uninstall_refuses_foreign_files_or_shortcut(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            installed = self.bundle_fixture(base / "dungeon-mice-app")
            foreign = installed / "personal-video.avi"; foreign.write_bytes(b"preserve")
            with self.assertRaises(ValueError): setup.uninstall_bundle(base)
            self.assertTrue(foreign.exists()); foreign.unlink()
            shortcut = base / "applications/dungeon-mice.desktop"
            shortcut.parent.mkdir(); shortcut.write_text("foreign application")
            with self.assertRaises(ValueError): setup.uninstall_bundle(base)
            self.assertTrue(installed.exists()); self.assertEqual(shortcut.read_text(), "foreign application")

    def test_foreign_destination_and_extra_user_files_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = self.bundle_fixture(root / "source")
            destination = root / "foreign"
            destination.mkdir()
            personal = destination / "personal.txt"
            personal.write_text("preserve")
            with self.assertRaises((ValueError, FileNotFoundError)):
                setup.install_bundle(source, destination)
            self.assertEqual(personal.read_text(), "preserve")
            installed = setup.install_bundle(source, root / "installed")
            (installed / "personal.txt").write_text("preserve too")
            with self.assertRaises(ValueError):
                setup.install_bundle(source, installed)
            self.assertEqual((installed / "personal.txt").read_text(), "preserve too")

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
