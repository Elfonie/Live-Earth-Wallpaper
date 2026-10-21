"""Builds the Wallpaper Engine / browser version in web/: shaders copied from the Android assets, textures re-encoded.

Run after make_textures.py and make_sky.py (it reads their level-0 PNGs in tools/build) and whenever a shader changes.
Textures: day/night/moon JPEG (sRGB), topography+ocean mask lossless WebP (alpha is data), Milky Way lossy WebP (8k), clouds the 8k source JPEG.
"""
import shutil
from pathlib import Path

from PIL import Image

Image.MAX_IMAGE_PIXELS = None
ROOT = Path(__file__).resolve().parent
BUILD, SRC = ROOT / "build", ROOT / "src"
ASSETS = ROOT.parent / "app" / "src" / "main" / "assets"
OUT = ROOT.parent / "web"


def size(p):
    return f"{p.stat().st_size / 1e6:.1f} MB"


def main():
    (OUT / "shaders").mkdir(parents=True, exist_ok=True)
    (OUT / "assets").mkdir(parents=True, exist_ok=True)
    for name in ("earth.vert", "earth.frag", "resolve.frag", "sky_common.glsl", "atmosphere.glsl"):
        shutil.copy(ASSETS / name, OUT / "shaders" / name)
    for name in ("atmosphere_transmittance.bin", "atmosphere_scatter_r.bin", "atmosphere_scatter_m.bin",
                 "atmosphere_scatter_ms.bin", "sky_stars.bin"):
        shutil.copy(ASSETS / name, OUT / "assets" / name)
    shutil.copy(SRC / "clouds_static_8k.jpg", OUT / "assets" / "clouds.jpg")
    for m in range(1, 13):
        Image.open(BUILD / f"earth_day_{m:02d}.png").convert("RGB").save(OUT / "assets" / f"day_{m:02d}.jpg", quality=93, subsampling=0)
    Image.open(BUILD / "earth_night.png").convert("RGB").save(OUT / "assets" / "night.jpg", quality=93, subsampling=0)
    Image.open(BUILD / "moon.png").convert("RGB").save(OUT / "assets" / "moon.jpg", quality=93, subsampling=0)
    Image.open(BUILD / "earth_topography_spec.png").convert("RGBA").save(OUT / "assets" / "topo.webp", lossless=True, exact=True, quality=100, method=6)  # exact: keep the normals where alpha (the ocean mask) is 0
    Image.open(BUILD / "sky_milkyway_8k.png").convert("RGB").save(OUT / "assets" / "milkyway.webp", quality=96, method=4)  # run `PNG_ONLY=1 make_sky.py mw` first
    for p in sorted((OUT / "assets").iterdir()):
        print(p.name, size(p))


if __name__ == "__main__":
    main()
