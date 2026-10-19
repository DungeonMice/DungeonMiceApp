#!/bin/sh
set -eu
if ! command -v apt-get >/dev/null 2>&1; then
    printf '%s\n' 'Instala VLC, GTK 3, libGL, GLib y ALSA usando el gestor de tu distribucion.' >&2
    exit 1
fi
as_root() {
    if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi
}
as_root apt-get update
gtk=libgtk-3-0
alsa=libasound2
glib=libglib2.0-0
if apt-cache show libgtk-3-0t64 >/dev/null 2>&1; then gtk=libgtk-3-0t64; fi
if apt-cache show libasound2t64 >/dev/null 2>&1; then alsa=libasound2t64; fi
if apt-cache show libglib2.0-0t64 >/dev/null 2>&1; then glib=libglib2.0-0t64; fi
as_root apt-get install vlc ffmpeg "$gtk" "$alsa" "$glib" libgl1 libx11-6 libxext6 libxi6 libxrender1 libxtst6 fonts-dejavu-core
