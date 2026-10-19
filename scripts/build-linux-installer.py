"""Wrap the verified Linux tarball in a single self-extracting shell installer."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def write_installer(archive, template, destination):
    archive, destination = Path(archive), Path(destination)
    with archive.open("rb") as source:
        checksum = hashlib.file_digest(source, "sha256").hexdigest()
    header = Path(template).read_text(encoding="utf-8").replace("\r\n", "\n")
    if not header.endswith("__DUNGEONMICE_PAYLOAD_BELOW__\n"):
        raise ValueError("Invalid installer header")
    header = header.replace("@SHA256@", checksum).replace("@PAYLOAD_LINE@", str(header.count("\n") + 1))
    fd, filename = tempfile.mkstemp(prefix=".DungeonMice-installer-", dir=destination.parent)
    temporary = Path(filename)
    try:
        with os.fdopen(fd, "wb") as out, archive.open("rb") as source:
            out.write(header.encode("utf-8"))
            shutil.copyfileobj(source, out)
        temporary.chmod(0o755)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    scripts = Path(__file__).resolve().parent
    if not args.archive.name.endswith(".tar.gz"):
        parser.error("Expected a .tar.gz archive")
    subprocess.run([sys.executable, "-B", str(scripts / "check-linux-archive.py"), str(args.archive)], check=True)
    target = args.archive.with_name(args.archive.name.removesuffix(".tar.gz") + ".run")
    write_installer(args.archive, scripts / "linux/installer.run.sh", target)
    print("Single-file installer: " + str(target), flush=True)


if __name__ == "__main__":
    main()
