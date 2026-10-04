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
