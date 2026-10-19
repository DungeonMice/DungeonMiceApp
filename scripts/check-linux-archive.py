"""Inspect a Linux release without extracting or running its native binaries."""
import hashlib
import io
import json
from pathlib import Path
import posixpath
import sys
import tarfile
import zipfile


def check(path):
    with tarfile.open(path, "r:gz") as archive:
        entries = archive.getmembers()
        members = {item.name.rstrip("/"): item for item in entries}
        assert len(members) == len(entries), "Duplicate archive names"
        for item in entries:
            assert item.name.startswith("DungeonMice/") and ".." not in item.name.split("/") and "\\" not in item.name, item.name
            assert not item.name.endswith((".avi", ".mov", ".mp4", "/database.enc")), item.name
            if item.name.endswith(".exe"):
                assert "/site-packages/pip/_vendor/distlib/" in item.name, item.name
            if item.issym() or item.islnk():
                target = item.linkname if item.islnk() else posixpath.join(posixpath.dirname(item.name), item.linkname)
                target = posixpath.normpath(target)
                assert target.startswith("DungeonMice/") and target in members, (item.name, target)
        def read(name):
            return archive.extractfile("DungeonMice/" + name).read()
        for name in ("DungeonMice", "instalar.sh", "setup.sh", "install-dependencies.sh", "check-linux.sh"):
            assert members["DungeonMice/" + name].mode & 0o111, name
            assert read(name).startswith(b"#!/bin/sh\n") and b"\r" not in read(name), name
        for name in ("runtime/bin/java", "backend/runtime/python/bin/python3"):
            file = members["DungeonMice/" + name]
            visited = set()
            while file.issym() or file.islnk():
                assert file.name not in visited, "Symlink loop"
                visited.add(file.name)
                target = file.linkname if file.islnk() else posixpath.normpath(posixpath.join(posixpath.dirname(file.name), file.linkname))
                file = members[target]
            assert file.mode & 0o111, name
            with archive.extractfile(file) as stream:
                header = stream.read(20)
            assert header[:6] == b"\x7fELF\x02\x01" and int.from_bytes(header[18:20], "little") == 62, name
        jar = read("app/DungeonMiceApp-1.0-SNAPSHOT.jar")
        manifest = json.loads(read("bundle-manifest.json"))
        assert hashlib.sha256(jar).hexdigest() == manifest["jarSha256"]
        with zipfile.ZipFile(io.BytesIO(jar)) as library:
            assert library.read("libglass.so").startswith(b"\x7fELF")
            assert "glass.dll" not in library.namelist()
            assert "com/phys/dungeonmiceapp/ui/EventFrameArchive.class" in library.namelist()
        backend = json.loads(read("backend/bundle-manifest.json"))
        for item in backend["engineFiles"]:
            assert hashlib.sha256(read("backend/" + item["path"])).hexdigest() == item["sha256"], item["path"]
        assert any("site-packages/cv2/" in name and name.endswith(".so") for name in members)
        assert "DungeonMice/backend/scripts/python_bootstrap.py" in members
        print("PASS Linux ELF x86_64 runtimes, executable modes, links, JavaFX, flag classes and unchanged engine hashes")
    print("PASS Archive excludes videos, personal databases and Windows application runtimes")
    print("Linux runtime/UI execution still requires ./check-linux.sh --ui on Linux")


if __name__ == "__main__":
    check(Path(sys.argv[1]))
