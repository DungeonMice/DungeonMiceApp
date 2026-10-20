"""Build a Linux x86_64 portable tarball on Windows or Linux, without running ELF files."""
import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ADAPTERS = ("dungeonmice_api_bridge.py", "python_api_example.py", "frame_sessions.py",
            "experiment_config.py", "python_bootstrap.py")


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(artifact, cache):
    name = artifact["file"]
    if Path(name).name != name or not re.fullmatch(r"[a-f0-9]{64}", artifact["sha256"]):
        raise ValueError("Invalid locked artifact")
    target = cache / name
    if target.exists():
        if digest(target) != artifact["sha256"]:
            raise ValueError("Cached artifact hash mismatch: " + str(target))
        return target
    fd, filename = tempfile.mkstemp(prefix=".download-", dir=cache)
    temporary = Path(filename)
    try:
        print("Downloading " + name, flush=True)
        request = urllib.request.Request(artifact["url"], headers={"User-Agent": "DungeonMice-build"})
        with os.fdopen(fd, "wb") as out, urllib.request.urlopen(request, timeout=90) as stream:
            shutil.copyfileobj(stream, out)
        if digest(temporary) != artifact["sha256"]:
            raise ValueError("Download hash mismatch: " + name)
        temporary.replace(target)
        return target
    finally:
        temporary.unlink(missing_ok=True)


def safe_name(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError("Unsafe archive name: " + name)
    return path


class Bundle:
    def __init__(self, output):
        self.output = output
        self.names = set()

    def add(self, info, stream=None):
        safe_name(info.name)
        if info.name in self.names:
            if info.isdir():
                return
            raise ValueError("Duplicate bundle path: " + info.name)
        self.names.add(info.name)
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        info.mode &= 0o777
        self.output.addfile(info, stream)

    def data(self, name, data, mode=0o644):
        info = tarfile.TarInfo("DungeonMice/" + name)
        info.size, info.mode = len(data), mode
        self.add(info, io.BytesIO(data))

    def file(self, name, path, executable=False):
        if executable:
            self.data(name, Path(path).read_bytes().replace(b"\r\n", b"\n"), 0o755 if executable else 0o644)
        else:
            info = tarfile.TarInfo("DungeonMice/" + name)
            info.size = Path(path).stat().st_size
            info.mode = 0o644
            with Path(path).open("rb") as stream:
                self.add(info, stream)

    def runtime(self, archive, destination):
        with tarfile.open(archive, "r:gz") as source:
            members = source.getmembers()
            roots = {safe_name(item.name).parts[0] for item in members}
            if len(roots) != 1:
                raise ValueError("Runtime archive requires one top-level directory")
            root = roots.pop()
            for item in members:
                path = safe_name(item.name)
                relative = str(path.relative_to(root))
                mapped = "DungeonMice/" + destination + ("/" + relative if relative != "." else "")
                info = copy.copy(item)
                info.pax_headers = {}
                info.name = mapped
                if item.issym() or item.islnk():
                    link = item.linkname if item.islnk() else posixpath.join(posixpath.dirname(item.name), item.linkname)
                    link = posixpath.normpath(link)
                    safe_name(link)
                    if PurePosixPath(link).parts[0] != root:
                        raise ValueError("Runtime link escapes archive root")
                    if item.islnk():
                        info.linkname = "DungeonMice/" + destination + "/" + str(PurePosixPath(link).relative_to(root))
                    self.add(info)
                elif item.isfile():
                    with source.extractfile(item) as stream:
                        self.add(info, stream)
                elif item.isdir():
                    self.add(info)
                else:
                    raise ValueError("Unsupported runtime entry: " + item.name)

    def wheel(self, wheel, python_version):
        base = "DungeonMice/backend/runtime/python/lib/python" + python_version + "/site-packages/"
        with zipfile.ZipFile(wheel) as source:
            for item in source.infolist():
                path = safe_name(item.filename)
                if item.is_dir():
                    continue
                if path.parts[0].endswith(".data"):
                    if len(path.parts) < 3 or path.parts[1] not in ("purelib", "platlib"):
                        continue
                    path = PurePosixPath(*path.parts[2:])
                info = tarfile.TarInfo(base + str(path))
                info.size, info.mode = item.file_size, 0o644
                with source.open(item) as stream:
                    self.add(info, stream)


def build(args):
    lock = json.loads((ROOT / "scripts/linux-bundle-lock.json").read_text())
    project = args.python_project.resolve(strict=True)
    protected = project / "src/RunExperiment_per_frame.py"
    original_hash = digest(protected)
    for name in ("src/RunExperiment.py", "src/input_loader.py", "LICENSE.txt"):
        if not (project / name).is_file():
            raise ValueError("Missing Python source: " + name)
    args.cache.mkdir(parents=True, exist_ok=True)
    jre, python, *wheels = [download(item, args.cache) for item in (lock["java"], lock["python"], *lock["packages"])]
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / ("DungeonMice-" + args.version + "-linux-x86_64.tar.gz")
    if destination.exists() and not args.force:
        raise FileExistsError("Archive already exists; use --force to replace: " + str(destination))
    with tempfile.TemporaryDirectory(prefix="dungeonmice-linux-build-") as work:
        stage = Path(work)
        # Compile a source-only copy so Windows target/dist artifacts remain intact.
        shutil.copy2(ROOT / "pom.xml", stage / "pom.xml")
        shutil.copytree(ROOT / "src/main", stage / "src/main")
        env = dict(os.environ)
        if args.java_home:
            env["JAVA_HOME"] = str(args.java_home)
        maven = shutil.which("mvn.cmd" if os.name == "nt" else "mvn")
        if not maven:
            raise RuntimeError("Maven is required on the build machine")
        subprocess.run([maven, "-q", "-Djavafx.platform=linux", "-Dmaven.test.skip=true", "clean", "package"], cwd=stage, env=env, check=True)
        jar = stage / "target/DungeonMiceApp-1.0-SNAPSHOT.jar"
        with zipfile.ZipFile(jar) as source:
            if source.read("libglass.so")[:4] != b"\x7fELF" or "glass.dll" in source.namelist():
                raise ValueError("Application must include Linux JavaFX native libraries")
            if "com/phys/dungeonmiceapp/ui/EventFrameArchive.class" not in source.namelist():
                raise ValueError("The flag event implementation is missing")
        engine = []
        for source in sorted((project / "src").rglob("*")):
            relative = source.relative_to(project / "src")
            if source.suffix not in (".py", ".json") or not source.is_file() or any(part.startswith(".") or part == "__pycache__" for part in relative.parts):
                continue
            if source.is_symlink():
                raise ValueError("Unexpected Python source symlink")
            engine.append((source, "engine/src/" + relative.as_posix()))
        manifest = dict(schemaVersion=1, platform="linux-x86_64", version=args.version,
                        pythonVersion=lock["pythonVersion"], downloads=lock,
                        engineFiles=[dict(path=path, sha256=digest(source)) for source, path in engine])
        fd, filename = tempfile.mkstemp(prefix=".DungeonMice-", suffix=".partial", dir=args.output)
        os.close(fd)
        temporary = Path(filename)
        try:
            print("Assembling Linux archive...", flush=True)
            with tarfile.open(temporary, "w:gz", compresslevel=6) as output:
                bundle = Bundle(output)
                bundle.runtime(jre, "runtime")
                bundle.runtime(python, "backend/runtime/python")
                for wheel in wheels:
                    bundle.wheel(wheel, ".".join(lock["pythonVersion"].split(".")[:2]))
                bundle.file("app/" + jar.name, jar)
                for name in ADAPTERS:
                    bundle.file("backend/scripts/" + name, ROOT / "scripts" / name)
                for source, relative in engine:
                    bundle.file("backend/" + relative, source)
                bundle.file("backend/engine/LICENSE.txt", project / "LICENSE.txt")
                for name in ("DungeonMice", "instalar.sh", "desinstalar.sh", "setup.sh", "install-dependencies.sh", "check-linux.sh"):
                    bundle.file(name, ROOT / "scripts/linux" / name, executable=True)
                for name in ("setup-linux.py", "check-linux.py"):
                    bundle.file(name, ROOT / "scripts/linux" / name)
                bundle.file("DungeonMice.png", ROOT / "src/main/resources/images/dungeon-mouse-yellow.png")
                bundle.file("README.md", ROOT / "docs/LINUX_INSTALLER.md")
                for name in ("LICENSE", "THIRD_PARTY.md"):
                    bundle.file(name, ROOT / name)
                data = json.dumps(manifest, indent=2).encode("utf-8")
                bundle.data("backend/bundle-manifest.json", data)
                bundle.data("bundle-manifest.json", json.dumps(dict(version=args.version, platform="linux-x86_64", jarSha256=digest(jar))).encode("utf-8"))
            if digest(protected) != original_hash:
                raise RuntimeError("Original Python engine changed during build")
            subprocess.run([sys.executable, "-B", str(ROOT / "scripts/check-linux-archive.py"), str(temporary)], check=True)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    checksum = digest(destination)
    destination.with_name(destination.name + ".sha256").write_text(checksum + "  " + destination.name + "\n", encoding="ascii")
    print("Archive: " + str(destination), flush=True)
    print("SHA256: " + checksum, flush=True)
    subprocess.run([sys.executable, "-B", str(ROOT / "scripts/build-linux-installer.py"), str(destination)], check=True)
    print("Linux UI/runtime execution must be verified on Linux with ./check-linux.sh", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="1.0.8")
    parser.add_argument("--python-project", type=Path, default=ROOT.parent / "DungeonMice")
    parser.add_argument("--output", type=Path, default=ROOT / "dist/linux")
    cache_root = Path(os.environ.get("LOCALAPPDATA", str(Path.home() / ".cache")))
    parser.add_argument("--cache", type=Path, default=cache_root / "DungeonMice/build-cache/linux-x86_64")
    default_java = Path("C:/Program Files/Java/jdk-17") if os.name == "nt" else None
    parser.add_argument("--java-home", type=Path, default=default_java if default_java and default_java.is_dir() else None)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        parser.error("Version must be major.minor.patch")
    build(args)


if __name__ == "__main__":
    main()
