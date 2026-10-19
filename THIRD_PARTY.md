# Direct video playback

Playback requires a 64-bit VLC installation (https://www.videolan.org/vlc/).
Reload the Maven project in IntelliJ after updating its dependencies, then run
com.phys.dungeonmiceapp.App.

Videos are opened at their original paths through VLCJ file callbacks. Neither
playback nor previews transcode, copy, or create cached video files. Frame buffers
are transient memory; the database stores file references, not video contents.

VLCJ and VLCJ-JavaFX are licensed under GPLv3:
https://github.com/caprica/vlcj and https://github.com/caprica/vlcj-javafx.
Review their distribution requirements before distributing a combined application.
The native VLC libraries have their own licenses; see https://www.videolan.org/legal.html.

# Linux Runtime Bundle

The Linux tarball separately includes Eclipse Temurin Java 17 (including its
legal notices), Astral python-build-standalone and Linux dependency wheels.
Pinned URLs/hashes are in `scripts/linux-bundle-lock.json`. Runtime archive
notices and wheel `.dist-info` licenses remain in the tarball. Source projects:
https://adoptium.net/ and https://github.com/astral-sh/python-build-standalone.
Linux VLC/FFmpeg are installed through the distribution package manager and
are not vendored into the portable archive.

# Windows Python bundle

The Windows app-image includes the official embedded Python distribution and
vendored NumPy, OpenCV-contrib, OpenPyXL and et-xmlfile wheels. Exact versions,
upstream download URLs and SHA-256 digests are in `scripts/python-bundle-lock.json`.
Python's LICENSE.txt and wheel `.dist-info` license/notice files are retained in
`backend/runtime/python`. Consult those notices for their redistribution terms.
Python embedding documentation: https://docs.python.org/3/using/windows.html.

The original DungeonMice engine's `LICENSE.txt` is retained under `backend/engine`.
The generated bundle manifest records each engine source file's hash; the original
Python checkout is not edited. Review that license before distributing the engine.

The installer includes the signed Microsoft Visual C++ x64 redistributable as a
prerequisite. Its redistribution requires complying with Microsoft's applicable
license terms; see https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist.
VLC and the Microsoft shared runtime are not removed by Dungeon Mice's uninstaller.
