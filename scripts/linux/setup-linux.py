"""Register an extracted portable package in the current user's application menu."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

PAYLOAD = ("DungeonMice", "DungeonMice.png", "runtime", "backend", "app", "bundle-manifest.json",
           "README.md", "LICENSE", "THIRD_PARTY.md", "setup.sh", "setup-linux.py", "check-linux.sh",
           "check-linux.py", "install-dependencies.sh", "instalar.sh")


def validate_bundle(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError("La carpeta de instalacion no es valida")
    manifest = json.loads((root / "bundle-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("platform") != "linux-x86_64":
        raise ValueError("La carpeta no es un paquete Linux de Dungeon Mice")


def install_bundle(source, destination):
    source, destination = Path(source).resolve(), Path(destination)
    validate_bundle(source)
    if destination.is_symlink():
        raise ValueError("El destino no puede ser un enlace simbolico")
    destination = destination.resolve()
    if source == destination:
        return destination
    if source.is_relative_to(destination) or destination.is_relative_to(source):
        raise ValueError("La carpeta extraida y la instalacion deben estar separadas")
    if destination.exists():
        validate_bundle(destination)
        if any(item.name not in PAYLOAD for item in destination.iterdir()):
            raise ValueError("Hay archivos ajenos al programa en el destino; conservalos fuera antes de actualizar")
    for name in PAYLOAD:
        item = source / name
        if not item.exists() or item.is_symlink():
            raise ValueError("El paquete esta incompleto: " + name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Both directories stay on the destination filesystem for atomic renames.
    with tempfile.TemporaryDirectory(prefix=".dungeon-mice-install-", dir=destination.parent) as temporary:
        work = Path(temporary).resolve()
        if work.parent != destination.parent or not work.name.startswith(".dungeon-mice-install-"):
            raise ValueError("Directorio temporal de instalacion invalido")
        staged, backup = work / "new", work / "previous"
        staged.mkdir()
        for name in PAYLOAD:
            item = source / name
            if item.is_dir():
                shutil.copytree(item, staged / name, symlinks=True)
            else:
                shutil.copy2(item, staged / name)
        had_previous = destination.exists()
        if had_previous:
            destination.replace(backup)
        try:
            staged.replace(destination)
        except BaseException:
            if had_previous:
                backup.replace(destination)
            raise
        # Only generated program files from a validated install are retired here.
        if had_previous:
            validate_bundle(backup)
    return destination


def desktop_escape(value):
    if any(char in value for char in "\n\r\x00="):
        raise ValueError("Ruta de instalacion invalida")
    quoted = value.replace("\\", "\\\\").replace('"', '\\"').replace("`", "\\`").replace("$", "\\$").replace("%", "%%")
    return quoted.replace("\\", "\\\\")


def main():
    parser = argparse.ArgumentParser(description="Acceso directo de Dungeon Mice")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--install", action="store_true", help="Instalar dependencias, programa y acceso al menu")
    mode.add_argument("--uninstall", action="store_true", help="Quitar el acceso del menu; conservar todos los datos")
    args = parser.parse_args()
    if sys.platform != "linux":
        parser.error("Ejecuta este setup en Linux")
    root = Path(__file__).resolve().parent
    extracted = root
    validate_bundle(root)
    base = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
    if not base.is_absolute():
        base = Path.home() / ".local/share"
    target = base / "applications/dungeon-mice.desktop"
    if target.exists() and "X-DungeonMice-Managed=true" not in target.read_text(encoding="utf-8"):
        parser.error("El acceso existente no pertenece a este setup")
    if args.uninstall:
        if target.exists() and "X-DungeonMice-Managed=true" not in target.read_text(encoding="utf-8"):
            parser.error("El acceso existente no pertenece a este setup")
        target.unlink(missing_ok=True)
        print("Acceso del menu eliminado. La carpeta del programa y los datos se conservan.")
        return
    if args.install:
        if os.geteuid() == 0:
            parser.error("Ejecuta instalar.sh sin sudo; solo las dependencias requieren privilegios")
        destination = base / "dungeon-mice-app"
        # Refuse unrelated destinations before asking apt to make any changes.
        if destination.is_symlink():
            parser.error("El destino de instalacion no puede ser un enlace simbolico")
        if destination.exists():
            validate_bundle(destination)
        print("Preparando dependencias del sistema...", flush=True)
        subprocess.run([str(root / "install-dependencies.sh")], check=True)
        print("Instalando Dungeon Mice...", flush=True)
        root = install_bundle(root, destination)
    launcher = desktop_escape(str(root / "DungeonMice"))
    icon = str(root / "DungeonMice.png").replace("\\", "\\\\")
    if any(char in icon for char in "\n\r\x00"):
        parser.error("Ruta de icono invalida")
    content = ("[Desktop Entry]\nType=Application\nName=Dungeon Mice\n"
               f'Exec="{launcher}"\nIcon={icon}\nTerminal=false\n'
               "Categories=Science;Education;\nX-DungeonMice-Managed=true\n")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and "X-DungeonMice-Managed=true" not in target.read_text(encoding="utf-8"):
        parser.error("El acceso existente no pertenece a este setup")
    target.write_text(content, encoding="utf-8")
    print("Dungeon Mice instalado y agregado al menu." if args.install else "Dungeon Mice agregado al menu. Conserva esta carpeta en su ubicacion.")
    if args.install and root != extracted:
        print("Ya puedes eliminar la carpeta extraida del tar.gz; el programa tiene su propia copia.")
    print("Para iniciar directamente: " + str(root / "DungeonMice"))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print("No se pudo completar la instalacion: " + str(error), file=sys.stderr)
        sys.exit(1)
