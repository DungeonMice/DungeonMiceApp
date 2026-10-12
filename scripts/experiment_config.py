"""Resolve the native experiment preset from explicit region geometry."""
import json
import math


def select_config(options, regions):
    config = json.loads(options.config.read_text(encoding="utf-8"))
    if not regions:
        return config
    morris = len(regions) == 1 and regions[0]["type"] == "CircularFractionRegion"
    wanted = "MorrisPool" if morris else "CrossMaze" if all(r["type"] == "PolygonRegion" for r in regions) else config["labyrinth_type"]
    if wanted == "CrossMaze" and (len(regions) < 2 or any(r["type"] != "PolygonRegion" for r in regions)):
        raise ValueError("CrossMaze requiere al menos dos regiones poligonales guardadas.")
    if wanted == "MorrisPool":
        if not morris:
            raise ValueError("Morris requiere un unico cuadrante circular.")
        region = regions[0]
        span = region["fraction"] * 360 if "fraction" in region else region.get("angle_end", 0) - region.get("angle_start", 0)
        if not math.isfinite(span) or not math.isclose(span % 360, 90, abs_tol=1e-6):
            raise ValueError("Morris requiere un cuadrante de 90 grados.")
    if config["labyrinth_type"] != wanted:
        names = {"MorrisPool": "MorrisPool_escopolamina_s1.json", "CrossMaze": "CrossMaze_control_s1.json"}
        config = json.loads((options.python_project / "src" / names[wanted]).read_text(encoding="utf-8"))
    config["regions"] = regions
    return config


def validate_frame_regions(regions, width, height):
    for region in regions:
        if region.get("frameWidth", width) != width or region.get("frameHeight", height) != height:
            raise ValueError("La resolucion cambio. Vuelve a dibujar las regiones.")
        if region["type"] == "PolygonRegion":
            if any(x >= width or y >= height for x, y in region["points"]):
                raise ValueError("Vertices fuera del video.")
        elif region["type"] in {"CircleRegion", "CircularFractionRegion"}:
            x, y = region["center"]
            radius = region["radius"]
            if x - radius < 0 or y - radius < 0 or x + radius > width - 1 or y + radius > height - 1:
                raise ValueError("La piscina debe quedar dentro del video.")
