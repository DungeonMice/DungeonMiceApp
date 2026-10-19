#!/bin/sh
set -eu
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec "$APP_ROOT/backend/runtime/python/bin/python3" -I -B "$APP_ROOT/setup-linux.py" "$@"
