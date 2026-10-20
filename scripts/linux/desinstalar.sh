#!/bin/sh
set -eu
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [ "$(uname -s)" != Linux ] || [ "$(id -u)" -eq 0 ]; then
    printf '%s\n' 'Ejecuta desinstalar.sh en Linux sin sudo, con el usuario que instalo la app.' >&2
    exit 1
fi
exec "$APP_ROOT/backend/runtime/python/bin/python3" -I -B "$APP_ROOT/setup-linux.py" --uninstall "$@"
