"""Register an extracted portable package in the current user's application menu."""
import argparse
import os
from pathlib import Path
import sys


def desktop_escape(value):
    if any(char in value for char in "\n\r\x00="):
        raise ValueError("Ruta de instalacion invalida")
    quoted = value.replace("\\", "\\\\").replace('"', '\\"').replace("`", "\\`").replace("$", "\\$").replace("%", "%%")
    return quoted.replace("\\", "\\\\")


def main():
    parser = argparse.ArgumentParser(description="Acceso directo de Dungeon Mice")
    parser.add_argument("--uninstall", action="store_true", help="Quitar el acceso del menu; conservar todos los datos")
    args = parser.parse_args()
    if sys.platform != "linux":
        parser.error("Ejecuta este setup en Linux")
    root = Path(__file__).resolve().parent
    if not (root / "bundle-manifest.json").is_file():
        parser.error("El paquete esta incompleto")
    base = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
    if not base.is_absolute():
        base = Path.home() / ".local/share"
    target = base / "applications/dungeon-mice.desktop"
    if args.uninstall:
        if target.exists() and "X-DungeonMice-Managed=true" not in target.read_text(encoding="utf-8"):
            parser.error("El acceso existente no pertenece a este setup")
        target.unlink(missing_ok=True)
        print("Acceso del menu eliminado. La carpeta del programa y los datos se conservan.")
        return
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
    print("Dungeon Mice agregado al menu. Conserva la carpeta extraida en esta ubicacion.")
    print("Para iniciar directamente: " + str(root / "DungeonMice"))


if __name__ == "__main__":
    main()
