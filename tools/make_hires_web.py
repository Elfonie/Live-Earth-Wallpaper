"""Builds 8192x4096 day, night and relief textures for the PC version (web/) from the higher-resolution NASA sources.

Sources (not in the repo, see tools/README.md for URLs), put into one folder (default tools/src_hires):
  world.topo.bathy.2004MM.3x21600x10800.jpg   Blue Marble Next Generation with topography and bathymetry, 2 km per pixel
  BlackMarble_2016_3km_gray.jpg               NASA Black Marble 2016, grey, 13500x6750
  (the relief uses tools/src/elev.png, 21600x10800, as before)

usage: python make_hires_web.py OUT_DIR [month ...]     e.g.  python make_hires_web.py C:/tmp/hires 10
Writes OUT_DIR/day_MM.jpg, night.jpg and topo.webp; copy them over web/assets to use them.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
HIRES = ROOT / "src_hires"
W, H = 8192, 4096
METERS_PER_ELEV_UNIT = 35.0  # as in make_textures.py
RELIEF_EXAGGERATION = 6.0


def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055)


def day(month, out):
    p = HIRES / f"world.topo.bathy.2004{month:02d}.3x21600x10800.jpg"
    im = Image.open(p).convert("RGB").resize((W, H), Image.LANCZOS)
    im.save(out / f"day_{month:02d}.jpg", quality=92, subsampling=0)


def night(out, gain=1.0, gamma=1.0, halo=0.0, spread=0, lift=0.0, lift_exp=0.4):
    """The grey 2016 map holds only the lights (the 2012 map in use also carries a dim land/ocean base layer), so it is much
    darker everywhere except at the cities; `gain` scales the lights, `gamma` < 1 lifts the faint ones, `halo` adds a soft glow around the lights, `spread` grows each light by that many texels, `lift` brightens only the faint lights."""
    g = np.asarray(Image.open(HIRES / "BlackMarble_2016_3km_gray.jpg").convert("L").resize((W, H), Image.LANCZOS), dtype=np.float32) / 255.0
    if spread:  # small lights are smaller than a screen pixel and fade; the older 11 km map showed every town as a dot: grow each light a little
        from PIL import ImageFilter
        im0 = Image.fromarray((g * 255 + 0.5).astype(np.uint8), "L")
        grown = np.asarray(im0.filter(ImageFilter.MaxFilter(2 * spread + 1)), dtype=np.float32) / 255.0
        g = np.maximum(g, grown * 0.85)
    if halo > 0:  # a soft glow around every light (the haze above the cities), blurred in two sizes, added to the sharp lights
        from PIL import ImageFilter
        im = Image.fromarray((g * 255 + 0.5).astype(np.uint8), "L")
        soft = sum(np.asarray(im.filter(ImageFilter.GaussianBlur(r)), dtype=np.float32) / 255.0 * w for r, w in ((5, 0.6), (18, 0.4)))
        g = np.maximum(g, halo * soft * 4.0)
    if lift > 0:  # faint lights (rural towns) several times brighter, bright ones (above g = lift ** (1 / (1 - lift_exp))) unchanged
        g = np.maximum(g, lift * g ** lift_exp)
    g = np.clip(gain * g ** gamma, 0, 1)  # gamma below 1 lifts the faint lights (small towns) that would otherwise fall under the shader's threshold
    Image.fromarray((g * 255 + 0.5).astype(np.uint8), "L").convert("RGB").save(out / "night.jpg", quality=92, subsampling=0)


def topo(out):
    elev_full = Image.open(SRC / "elev.png").convert("L")
    ocean = (np.asarray(elev_full) == 0).astype(np.float32)
    ocean = np.array(Image.fromarray(ocean).resize((W, H), Image.BOX))
    elev = np.asarray(elev_full.resize((W, H), Image.BOX), dtype=np.float32) * METERS_PER_ELEV_UNIT
    day1 = srgb_to_linear(np.asarray(Image.open(SRC / "day_01.jpg").convert("RGB").resize((W, H), Image.LANCZOS), dtype=np.float32) / 255.0)
    lum = linear_to_srgb(day1 @ np.array([0.2126, 0.7152, 0.0722], np.float32))
    t = np.clip((lum - 0.65) / 0.2, 0, 1)
    ocean *= 1 - t * t * (3 - 2 * t)  # sea ice must not glint
    lat = (0.5 - (np.arange(H) + 0.5) / H) * np.pi
    texel_ns = np.pi * 6371000.0 / H
    texel_ew = (2 * np.pi * 6371000.0 / W) * np.maximum(np.cos(lat), 0.05)[:, None]
    gx = (np.roll(elev, -1, axis=1) - np.roll(elev, 1, axis=1)) / (2 * texel_ew)
    gy_down = np.gradient(elev, axis=0) / texel_ns
    n = np.stack([-gx * RELIEF_EXAGGERATION, gy_down * RELIEF_EXAGGERATION, np.ones_like(gx)], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    rgba = (np.clip(np.concatenate([n * 0.5 + 0.5, ocean[..., None]], -1), 0, 1) * 255 + 0.5).astype(np.uint8)
    Image.fromarray(rgba, "RGBA").save(out / "topo.webp", lossless=True, exact=True, quality=100, method=4)


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    months = [int(m) for m in sys.argv[2:]] or [10]
    for m in months:
        day(m, out)
    night(out, float(__import__("os").environ.get("NIGHT_GAIN", "1.0")), float(__import__("os").environ.get("NIGHT_GAMMA", "1.0")), float(__import__("os").environ.get("NIGHT_HALO", "0.0")), int(__import__("os").environ.get("NIGHT_SPREAD", "0")), float(__import__("os").environ.get("NIGHT_LIFT", "0.0")), float(__import__("os").environ.get("NIGHT_LIFT_EXP", "0.4")))
    topo(out)
    for p in sorted(out.iterdir()):
        print(p.name, f"{p.stat().st_size / 1e6:.1f} MB")
