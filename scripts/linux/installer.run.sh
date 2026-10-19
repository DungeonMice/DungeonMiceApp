#!/bin/sh
set -eu
if [ "$(uname -s)" != Linux ] || [ "$(uname -m)" != x86_64 ]; then
    printf '%s\n' 'Este instalador requiere Linux x86_64.' >&2
    exit 1
fi
if [ "$(id -u)" -eq 0 ]; then
    printf '%s\n' 'Ejecuta este instalador sin sudo. Se pedira la clave para las dependencias.' >&2
    exit 1
fi
TMP_BASE=$(CDPATH= cd -- "${TMPDIR:-/tmp}" && pwd -P)
work=$(mktemp -d "$TMP_BASE/dungeonmice-setup.XXXXXXXX")
cleanup() {
    case "$work" in
        "$TMP_BASE"/dungeonmice-setup.????????) rm -rf -- "$work" ;;
        *) printf '%s\n' 'No se pudo validar el directorio temporal.' >&2 ;;
    esac
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
printf '%s\n' 'Extrayendo Dungeon Mice...'
tail -n +@PAYLOAD_LINE@ "$0" > "$work/payload.tar.gz"
actual=$(sha256sum < "$work/payload.tar.gz")
actual=${actual%% *}
if [ "$actual" != '@SHA256@' ]; then
    printf '%s\n' 'El instalador esta incompleto o danado. Vuelve a copiarlo.' >&2
    exit 1
fi
tar -xzf "$work/payload.tar.gz" --no-same-owner -C "$work"
"$work/DungeonMice/instalar.sh" "$@"
exit 0
__DUNGEONMICE_PAYLOAD_BELOW__
