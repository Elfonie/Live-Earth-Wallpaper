"""Builds the static ASTC KTX1 textures (4096x2048; the clouds 8192x4096) from the NASA source images in tools/src.

Sources (see tools/README.md for URLs): day_01/04/07/10.jpg (seasonal), night.jpg, elev.png, clouds_static_8k.jpg, moon_2k.jpg
Output: app/src/main/assets/*.ktx (full mip chain) and tools/build/*.png (level 0, for tools/preview.py)
Requires: Pillow, numpy, tools/bin/astcenc-avx2.exe
"""
import struct
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
ROOT = Path(__file__).resolve().parent
SRC, BUILD, BIN = ROOT / "src", ROOT / "build", ROOT / "bin" / "astcenc-avx2.exe"
ASSETS = ROOT.parent / "app" / "src" / "main" / "assets"
W, H = 4096, 2048

GL_SRGB8_ALPHA8_ASTC_4x4 = 0x93D0
GL_SRGB8_ALPHA8_ASTC_6x6 = 0x93D4
GL_SRGB8_ALPHA8_ASTC = {"4x4": GL_SRGB8_ALPHA8_ASTC_4x4, "6x6": GL_SRGB8_ALPHA8_ASTC_6x6}
GL_RGBA_ASTC_4x4 = 0x93B0
GL_RGBA_ASTC_5x5 = 0x93B2
GL_RGBA_ASTC_6x6 = 0x93B4
SEASONS = tuple(f"{m:02d}" for m in range(1, 13))  # all twelve Blue Marble months; the app uses the current one

METERS_PER_ELEV_UNIT = 35.0  # 8-bit GEBCO elevation map: 252 ~ Everest
RELIEF_EXAGGERATION = 6.0    # real slopes are tiny at 10 km/texel; this makes the Alps visible


def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055)


def load_rgb(name, size=(W, H)):
    im = Image.open(SRC / name).convert("RGB").resize(size, Image.LANCZOS)
    return np.asarray(im, dtype=np.float32) / 255.0


def halve(a):
    """2x2 box filter; a dimension that is already 1 stays 1."""
    h, w = a.shape[:2]
    if h > 1:
        a = (a[0::2] + a[1::2]) * 0.5
    if w > 1:
        a = (a[:, 0::2] + a[:, 1::2]) * 0.5
    return a


def mip_chain(level0, fix=None):
    levels = [level0]
    while levels[-1].shape[0] > 1 or levels[-1].shape[1] > 1:
        nxt = halve(levels[-1])
        levels.append(fix(nxt) if fix else nxt)
    return levels


def astc_compress(rgba8, linear, thorough, tmp, block="4x4"):
    png, astc = tmp / "lvl.png", tmp / "lvl.astc"
    Image.fromarray(rgba8, "RGBA").save(png)
    mode = "-cl" if linear else "-cs"
    quality = "-thorough" if thorough else "-medium"
    subprocess.run([str(BIN), mode, str(png), str(astc), block, quality, "-silent"], check=True)
    return astc.read_bytes()[16:]  # strip the 16-byte .astc header, keep the raw blocks


def write_ktx(path, levels_rgba8, internal_format, linear, tmp, block="4x4"):
    blobs = [astc_compress(l, linear, i == 0, tmp, block) for i, l in enumerate(levels_rgba8)]
    h, w = levels_rgba8[0].shape[:2]
    header = b"\xabKTX 11\xbb\r\n\x1a\n" + struct.pack(
        "<13I", 0x04030201, 0, 1, 0, internal_format, 0x1908, w, h, 0, 0, 1, len(blobs), 0
    )
    with open(path, "wb") as f:
        f.write(header)
        for b in blobs:
            f.write(struct.pack("<I", len(b)) + b)
    print(f"{path.name}: {len(blobs)} levels, {path.stat().st_size / 1e6:.1f} MB")


def to_rgba8(rgb=None, alpha=None, rgba=None):
    if rgba is None:
        a = np.ones(rgb.shape[:2] + (1,), np.float32) if alpha is None else alpha[..., None]
        rgba = np.concatenate([rgb, a], axis=-1)
    return (np.clip(rgba, 0, 1) * 255 + 0.5).astype(np.uint8)


def srgb_texture(name, ktx_name, tmp, block="4x4", size=(W, H)):
    lin = srgb_to_linear(load_rgb(name, size))  # filter in linear light
    levels = [to_rgba8(linear_to_srgb(l)) for l in mip_chain(lin)]
    Image.fromarray(levels[0], "RGBA").save(BUILD / (ktx_name + ".png"))
    write_ktx(ASSETS / (ktx_name + ".ktx"), levels, GL_SRGB8_ALPHA8_ASTC[block], False, tmp, block)


def topography_spec(tmp):
    elev_full = Image.open(SRC / "elev.png").convert("L")
    ocean = (np.asarray(elev_full) == 0).astype(np.float32)  # exact zero = sea level / ocean
    ocean = np.array(Image.fromarray(ocean).resize((W, H), Image.BOX))
    elev = np.asarray(elev_full.resize((W, H), Image.BOX), dtype=np.float32) * METERS_PER_ELEV_UNIT

    # Sea ice is white in the day map and must not glint like open water.
    day = srgb_to_linear(load_rgb("day_01.jpg"))  # winter: the most sea ice, so the glint mask excludes it
    lum = linear_to_srgb(day @ np.array([0.2126, 0.7152, 0.0722], np.float32))
    t = np.clip((lum - 0.65) / 0.2, 0, 1)
    ocean *= 1 - t * t * (3 - 2 * t)

    lat = (0.5 - (np.arange(H) + 0.5) / H) * np.pi
    texel_ns = np.pi * 6371000.0 / H
    texel_ew = (2 * np.pi * 6371000.0 / W) * np.maximum(np.cos(lat), 0.05)[:, None]
    gx = (np.roll(elev, -1, axis=1) - np.roll(elev, 1, axis=1)) / (2 * texel_ew)
    gy_down = np.gradient(elev, axis=0) / texel_ns  # rows go south
    n = np.stack([-gx * RELIEF_EXAGGERATION, gy_down * RELIEF_EXAGGERATION, np.ones_like(gx)], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)  # x = east, y = north, z = up

    level0 = np.concatenate([n * 0.5 + 0.5, ocean[..., None]], -1)

    def renormalize(a):  # averaged normals get shorter; restore unit length
        v = a[..., :3] * 2 - 1
        v /= np.maximum(np.linalg.norm(v, axis=-1, keepdims=True), 1e-4)
        a[..., :3] = v * 0.5 + 0.5
        return a

    levels = [to_rgba8(rgba=l) for l in mip_chain(level0, renormalize)]
    Image.fromarray(levels[0], "RGBA").save(BUILD / "earth_topography_spec.png")
    write_ktx(ASSETS / "earth_topography_spec.ktx", levels, GL_RGBA_ASTC_4x4, True, tmp)


def clouds(tmp):
    """The static 8192x4096 visible-light cloud map (tools/src/clouds_static_8k.jpg, see README) as grey in .r."""
    gray = np.asarray(Image.open(SRC / "clouds_static_8k.jpg").convert("L"), dtype=np.float32) / 255.0
    rgba = np.stack([gray, gray, gray, np.ones_like(gray)], -1)  # shader reads .r
    levels = [to_rgba8(rgba=l) for l in mip_chain(rgba)]
    Image.fromarray(levels[0], "RGBA").resize((W, H), Image.BOX).save(BUILD / "earth_clouds.png")  # preview.py
    write_ktx(ASSETS / "earth_clouds.ktx", levels, GL_RGBA_ASTC_6x6, True, tmp, block="6x6")


if __name__ == "__main__":
    for d in (BUILD, ASSETS, BUILD / "tmp"):
        d.mkdir(parents=True, exist_ok=True)
    tmp = BUILD / "tmp"
    which = sys.argv[1:] or ["day", "night", "topo", "clouds", "moon"]
    if "day" in which:
        for month in SEASONS:
            srgb_texture(f"day_{month}.jpg", f"earth_day_{month}", tmp, block="6x6")  # smooth data: 6x6 halves the size
    if "night" in which:
        srgb_texture("night.jpg", "earth_night", tmp)
    if "topo" in which:
        topography_spec(tmp)
    if "clouds" in which:
        clouds(tmp)
    if "moon" in which:
        srgb_texture("moon_2k.jpg", "moon", tmp, size=(2048, 1024))  # NASA SVS LRO colour map, longitude 0 in the middle
