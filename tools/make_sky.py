"""Builds the star sky data for earth.frag.

Inputs  (tools/src): athyg_m11.csv.gz (AT-HYG v3.2 subset, stars brighter than magnitude 11: Hipparcos, Tycho-2, Gaia),
                     starmap_8k.exr / .npy (NASA SVS Deep Star Maps 2020, 8k; needs pip install OpenEXR scipy)
Outputs (app/src/main/assets): sky_stars.bin      cell grid, compact star table, colour table (layout below)
                               sky_milkyway.ktx   star-free diffuse Milky Way with its dust lanes, ASTC 6x6, 8192x4096
Everything is in the J2000 equatorial frame; earth.frag rotates view rays into it (precession + GMST).

The catalog stars are drawn individually and crisply, the 8k map only supplies the soft band behind them: at the
phone's scale one map texel covers about four screen pixels, so its own stars would be blurry blobs.

sky_stars.bin, little endian:
  header  : "SKY2", uint32 star count, uint32 cells wide (1440, RA 0.25 deg), uint32 cells high (720, Dec 0.25 deg)
  cells   : wide*high * (uint32 first star index, uint32 star count), row-major, row 0 = Dec -90..-89.75, column 0 = RA 0..0.25
  stars   : one RG32UI texel per star, 2048 per row, sorted by cell. Position within the cell is not stored as an
            angle but as two 16 bit fractions:
              x: bits 0..15 RA fraction, bits 16..31 Dec fraction
              y: bits 0..7 (magnitude + 2) * 16, bits 8..15 colour index, bits 16..31 seed for the twinkle noise
  colours : 256 RGBA8 entries, colour index i = B-V -0.4 + i * 2.4 / 255 as a display colour
"""
import csv
import gzip
import os
import struct
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import binary_dilation, gaussian_filter, grey_opening

from make_textures import ASSETS, BUILD, GL_RGBA_ASTC_6x6, mip_chain, to_rgba8, write_ktx

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
MAG_LIMIT = 11.0
EPOCH_YEARS_SINCE_J2000 = 26.0  # proper motion is applied up to 2026.0
CELL_DEG = 0.25
CELLS_W, CELLS_H = 1440, 720
ROW_TEXELS = 2048
MAP_SCALE = 0.042  # map value that becomes 1.0 (the Milky Way's bright band)
STAR_COLOR_SATURATION = 0.7  # dark-adapted vision sees stars paler than their blackbody colour


def wyman_cmf(lam):
    """CIE 1931 colour matching functions, multi-lobe Gaussian fit (Wyman, Sloan, Shirley 2013)."""
    def g(mu, s1, s2):
        s = np.where(lam < mu, s1, s2)
        return np.exp(-0.5 * ((lam - mu) / s) ** 2)
    x = 1.056 * g(599.8, 37.9, 31.0) + 0.362 * g(442.0, 16.0, 26.7) - 0.065 * g(501.1, 20.4, 26.2)
    y = 0.821 * g(568.8, 46.9, 40.5) + 0.286 * g(530.9, 16.3, 31.1)
    z = 1.217 * g(437.0, 11.8, 36.0) + 0.681 * g(459.0, 26.0, 13.8)
    return x, y, z


XYZ_TO_SRGB = np.array([[3.2406, -1.5372, -0.4986], [-0.9689, 1.8758, 0.0415], [0.0557, -0.2040, 1.0570]])


def blackbody_rgb(temp_k):
    lam = np.arange(380.0, 781.0, 5.0)
    planck = 1.0 / (lam ** 5 * (np.exp(1.4388e7 / (lam * temp_k)) - 1.0))
    xyz = np.array([np.sum(planck * c) for c in wyman_cmf(lam)])
    rgb = np.clip(XYZ_TO_SRGB @ xyz, 0, None)
    rgb /= rgb.max()
    return STAR_COLOR_SATURATION * rgb + (1 - STAR_COLOR_SATURATION)


def bv_to_temperature(bv):
    bv = np.clip(bv, -0.4, 2.0)
    return 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62))  # Ballesteros 2012


def read_catalog():
    ra, dec, mag, bv, pm_ra, pm_dec = [], [], [], [], [], []

    def num(text, default):
        return float(text) if text else default

    with gzip.open(SRC / "athyg_m11.csv.gz", "rt", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["proper"] == "Sol" or not r["mag"] or float(r["mag"]) > MAG_LIMIT:  # a few bad entries slip into the subset
                continue
            ra.append(float(r["ra"]) * 15.0)  # hours -> degrees
            dec.append(float(r["dec"]))
            mag.append(float(r["mag"]))
            bv.append(num(r["ci"], 0.6))
            pm_ra.append(num(r["pm_ra"], 0.0))  # mas/yr, already times cos(dec)
            pm_dec.append(num(r["pm_dec"], 0.0))
    ra, dec, mag, bv = map(np.array, (ra, dec, mag, bv))
    ra = ra + np.array(pm_ra) * EPOCH_YEARS_SINCE_J2000 / 3.6e6 / np.maximum(np.cos(np.radians(dec)), 0.01)
    dec = np.clip(dec + np.array(pm_dec) * EPOCH_YEARS_SINCE_J2000 / 3.6e6, -90, 90)
    return ra % 360.0, dec, mag, bv


def build_stars():
    ra, dec, mag, bv = read_catalog()
    cell_x = np.minimum((ra / CELL_DEG).astype(np.int64), CELLS_W - 1)
    cell_y = np.clip(((dec + 90) / CELL_DEG).astype(np.int64), 0, CELLS_H - 1)
    cell = cell_y * CELLS_W + cell_x
    order = np.argsort(cell, kind="stable")
    ra, dec, mag, bv, cell, cell_x, cell_y = (a[order] for a in (ra, dec, mag, bv, cell, cell_x, cell_y))

    counts = np.bincount(cell, minlength=CELLS_W * CELLS_H)
    starts = np.concatenate([[0], np.cumsum(counts)[:-1]])
    cells = np.stack([starts, counts], -1).astype("<u4")

    n = len(ra)
    fx = np.clip((ra / CELL_DEG - cell_x) * 65535, 0, 65535).round().astype(np.uint32)
    fy = np.clip(((dec + 90) / CELL_DEG - cell_y) * 65535, 0, 65535).round().astype(np.uint32)
    mag8 = np.clip(np.round((mag + 2.0) * 16), 0, 255).astype(np.uint32)
    colour = np.clip(np.round((bv + 0.4) / 2.4 * 255), 0, 255).astype(np.uint32)
    seed = (np.arange(n) & 0xFFFF).astype(np.uint32)

    rows = (n + ROW_TEXELS - 1) // ROW_TEXELS
    table = np.zeros((rows * ROW_TEXELS, 2), "<u4")
    table[:n, 0] = fx | (fy << 16)
    table[:n, 1] = mag8 | (colour << 8) | (seed << 16)

    lut = np.zeros((256, 4), np.uint8)
    for i in range(256):
        lut[i, :3] = np.round(blackbody_rgb(bv_to_temperature(-0.4 + i * 2.4 / 255)) * 255)
        lut[i, 3] = 255

    with open(ASSETS / "sky_stars.bin", "wb") as f:
        f.write(b"SKY2" + struct.pack("<3I", n, CELLS_W, CELLS_H))
        f.write(cells.tobytes())
        f.write(table.tobytes())
        f.write(lut.tobytes())
    print(f"sky_stars.bin: {n} stars to mag {mag.max():.1f}, max {counts.max()} per cell, "
          f"{(ASSETS / 'sky_stars.bin').stat().st_size / 1e6:.1f} MB")


def build_milky_way(tmp):
    npy = SRC / "starmap_8k.npy"
    if not npy.exists():  # one-time conversion of the NASA EXR (half float RGB) to a plain array
        import OpenEXR
        np.save(npy, OpenEXR.File(str(SRC / "starmap_8k.exr")).channels()["RGB"].pixels.astype(np.float32))
    img = np.load(npy)  # 4096 x 8192 x 3, linear
    # A grey opening (minimum, then maximum) with a round 7 px footprint (0.3 deg) erases point sources but keeps
    # the band and its dust lanes (a square footprint left square plateaus). A blur and a cap on blobs that stand
    # out from their surroundings hide residual halos; subtracting the dark-sky floor keeps high latitudes black.
    yy, xx = np.mgrid[-3:4, -3:4]
    disk = ((yy ** 2 + xx ** 2) <= 9)[:, :, None]
    diffuse = gaussian_filter(grey_opening(img, footprint=disk), sigma=(1.5, 1.5, 0))
    surroundings = gaussian_filter(diffuse[::4, ::4], sigma=(6, 6, 0)).repeat(4, axis=0).repeat(4, axis=1)
    diffuse = np.minimum(diffuse, 2.0 * surroundings + 5e-4)
    # Bright stars leave plateaus wider than the 7 px opening (flat discs around the star): a much bigger opening
    # (about 2 degrees, at quarter resolution) finds what the sky looks like without them, and blobs above it are cut.
    yy, xx = np.mgrid[-6:7, -6:7]
    big = grey_opening(diffuse[::4, ::4], footprint=((yy ** 2 + xx ** 2) <= 36)[:, :, None])
    big = gaussian_filter(big, sigma=(1.5, 1.5, 0)).repeat(4, axis=0).repeat(4, axis=1)
    cut = (diffuse > 1.25 * big + 5e-4).any(axis=2)
    cut = binary_dilation(cut, iterations=4)[:, :, None]  # fill the cut spots from their surroundings, not with holes
    keep = ~cut
    filled = gaussian_filter(diffuse * keep, sigma=(8, 8, 0)) / np.maximum(gaussian_filter(keep.astype(np.float32), sigma=(8, 8, 0)), 1e-3)
    diffuse = np.where(cut, np.minimum(filled, 1.25 * big + 5e-4), diffuse)
    diffuse = np.clip(diffuse - np.percentile(diffuse[::4, ::4], 25, axis=(0, 1)), 0, None)
    linear = mip_chain(diffuse / MAP_SCALE)
    levels = [to_rgba8((np.clip(l, 0, 1) ** (1 / 2.2)).astype(np.float32)) for l in linear]  # gamma keeps faint detail
    Image.fromarray(levels[0], "RGBA").resize((4096, 2048), Image.BOX).save(BUILD / "sky_milkyway.png")  # preview.py
    Image.fromarray(levels[0], "RGBA").convert("RGB").save(BUILD / "sky_milkyway_8k.png")  # make_web.py
    if os.environ.get("PNG_ONLY"):
        return
    write_ktx(ASSETS / "sky_milkyway.ktx", levels, GL_RGBA_ASTC_6x6, True, tmp, block="6x6")


if __name__ == "__main__":
    import sys
    tmp = BUILD / "tmp"
    for d in (ASSETS, BUILD, tmp):
        d.mkdir(parents=True, exist_ok=True)
    if "stars" in sys.argv[1:] or len(sys.argv) == 1:
        build_stars()
    if "mw" in sys.argv[1:] or len(sys.argv) == 1:
        build_milky_way(tmp)
