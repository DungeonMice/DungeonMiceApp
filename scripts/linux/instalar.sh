#!/bin/sh
set -eu
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [ "$(uname -s)" != Linux ] || [ "$(uname -m)" != x86_64 ]; then
    printf '%s\n' 'Este instalador requiere Linux x86_64.' >&2
    exit 1
fi
if [ "$(id -u)" -eq 0 ]; then
    printf '%s\n' 'Ejecuta ./instalar.sh sin sudo. Se pedira la clave solo para las dependencias.' >&2
    exit 1
fi
exec "$APP_ROOT/backend/runtime/python/bin/python3" -I -B "$APP_ROOT/setup-linux.py" --install "$@"
