"""Renders app/src/main/assets/earth.frag on the desktop (moderngl) to a PNG, to check orientation, colours and lighting.

Usage: python preview.py [ISO time, default now] [out.png]   (env SHADER=path to try another earth.frag)
Uses the level-0 PNGs from tools/build (run make_textures.py first). The camera, uniforms and Sun/Moon
positions mirror EarthRenderer.kt; the Python astronomy below is the same math as Astronomy.kt.
Requires: pip install moderngl pillow numpy
"""
import os
import struct
import sys
from datetime import datetime, timezone
from math import asin, atan, atan2, cos, degrees, radians, sin, tan
from pathlib import Path

import moderngl
sys.path.insert(0, str(Path(__file__).resolve().parent))  # python -I leaves the script's folder off the path
from shader_expand import expand
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT.parent / "app" / "src" / "main" / "assets"
W, H = 1280, 2856
FOV_Y_DEG, FILL_X, LATITUDE_DEG, PERIOD_S = 30.0, 0.92, 21.4, 24 * 60.0  # mirror OrbitCamera.kt
DRIFT_PERIOD_S, VIEW_L_DEG, BAND_TILT_DEG = 3 * 3600.0, 320.0, 35.0  # mirror SkyFrame.kt


def gmst(jd):
    t = (jd - 2451545.0) / 36525
    return (280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t**3 / 38710000) % 360


def sun_eq(jd):
    n = jd - 2451545.0
    g = radians(357.528 + 0.9856003 * n)
    lam = radians(280.460 + 0.9856474 * n + 1.915 * sin(g) + 0.020 * sin(2 * g))
    eps = radians(23.439 - 0.0000004 * n)
    return degrees(atan2(cos(eps) * sin(lam), cos(lam))) % 360, degrees(asin(sin(eps) * sin(lam)))


LON_T = [(6288774, 0, 0, 1, 0), (1274027, 2, 0, -1, 0), (658314, 2, 0, 0, 0), (213618, 0, 0, 2, 0), (-185116, 0, 1, 0, 0), (-114332, 0, 0, 0, 2), (58793, 2, 0, -2, 0), (57066, 2, -1, -1, 0), (53322, 2, 0, 1, 0), (45758, 2, -1, 0, 0), (-40923, 0, 1, -1, 0), (-34720, 1, 0, 0, 0), (-30383, 0, 1, 1, 0), (15327, 2, 0, 0, -2), (-12528, 0, 0, 1, 2), (10980, 0, 0, 1, -2)]
LAT_T = [(5128122, 0, 0, 0, 1), (280602, 0, 0, 1, 1), (277693, 0, 0, 1, -1), (173237, 2, 0, 0, -1), (55413, 2, 0, -1, 1), (46271, 2, 0, -1, -1), (32573, 2, 0, 0, 1), (17198, 0, 0, 2, 1), (9266, 2, 0, 1, -1), (8822, 0, 0, 2, -1), (8216, 2, -1, 0, -1), (4324, 2, 0, -2, -1), (4200, 2, 0, 1, 1), (-3359, 2, 1, 0, -1)]


def moon_eq(jd):
    t = (jd - 2451545.0) / 36525
    lp = 218.3164477 + 481267.88123421 * t
    d, m = radians(297.8501921 + 445267.1114034 * t), radians(357.5291092 + 35999.0502909 * t)
    mp, f = radians(134.9633964 + 477198.8675055 * t), radians(93.2720950 + 483202.0175233 * t)
    e = 1 - 0.002516 * t - 0.0000074 * t * t

    def s(tab):
        return sum(c * e ** abs(mm) * sin(dd * d + mm * m + mpp * mp + ff * f) for c, dd, mm, mpp, ff in tab) * 1e-6

    lam, bet = radians(lp + s(LON_T)), radians(s(LAT_T))
    eps = radians(23.439291 - 0.0130042 * t)
    ra = degrees(atan2(sin(lam) * cos(eps) - tan(bet) * sin(eps), cos(lam))) % 360
    return ra, degrees(asin(sin(bet) * cos(eps) + cos(bet) * sin(eps) * sin(lam)))


DIST_T = [(0, 0, 1, 0, -20905355), (2, 0, -1, 0, -3699111), (2, 0, 0, 0, -2955968), (0, 0, 2, 0, -569925), (0, 1, 0, 0, 48888), (0, 0, 0, 2, -3149), (2, 0, -2, 0, 246158), (2, -1, -1, 0, -152138), (2, 0, 1, 0, -170733), (2, -1, 0, 0, -204586), (0, 1, -1, 0, -129620), (1, 0, 0, 0, 108743), (0, 1, 1, 0, 104755), (2, 0, 0, -2, 10321), (0, 0, 1, 2, 79661), (0, 0, 1, -2, 34782), (4, 0, -1, 0, -23210), (4, 0, -2, 0, -21636), (2, 1, -1, 0, 24208), (2, 1, 0, 0, 30824), (1, 0, -1, 0, -8379), (1, 1, 0, 0, -16675), (2, -1, 1, 0, -12831), (2, 0, 2, 0, -10445), (4, 0, 0, 0, -11650), (2, 0, -3, 0, 14403), (0, 1, -2, 0, -7003)]


def moon_distance(jd):  # Earth radii, same series as Astronomy.moonDistanceKm
    t = (jd - 2451545.0) / 36525
    d, m = radians(297.8501921 + 445267.1114034 * t), radians(357.5291092 + 35999.0502909 * t)
    mp, f = radians(134.9633964 + 477198.8675055 * t), radians(93.2720950 + 483202.0175233 * t)
    e = 1 - 0.002516 * t - 0.0000074 * t * t
    km = 385000.56 + sum(c * e ** abs(mm) * cos(dd * d + mm * m + mpp * mp + ff * f) for dd, mm, mpp, ff, c in DIST_T) * 1e-3
    return km / 6378.14


def earth_fixed(eq, g):
    lon, dec = radians(eq[0] - g), radians(eq[1])
    return np.array([cos(dec) * cos(lon), sin(dec), -cos(dec) * sin(lon)], np.float32)


def rot_z(a):
    return np.array([[cos(a), -sin(a), 0], [sin(a), cos(a), 0], [0, 0, 1]])


def rot_y(a):
    return np.array([[cos(a), 0, sin(a)], [0, 1, 0], [-sin(a), 0, cos(a)]])


TO_GALACTIC = np.array([[-0.0548755604, -0.8734370902, -0.4838350155],
                        [0.4941094279, -0.4448296300, 0.7469822445],
                        [-0.8676661490, -0.1980763734, 0.4559837762]])


def orbit_pose(jd, dist):
    """Same math as OrbitCamera.pose: circular orbit at a fixed latitude, looking at the centre, Earth axis up."""
    u = ((jd - 2451545.0) * 86400.0 % PERIOD_S) / PERIOD_S * 2 * np.pi
    lat = radians(LATITUDE_DEG)
    inertial = dist * np.array([cos(lat) * cos(u), -cos(lat) * sin(u), sin(lat)])
    g = radians(gmst(jd))
    pos = np.array([[cos(g), sin(g), 0], [0, 0, 1], [sin(g), -cos(g), 0]]) @ inertial
    n = pos / np.linalg.norm(pos)
    up = np.array([0.0, 1.0, 0.0]) - n[1] * n
    up /= np.linalg.norm(up)
    fwd = -pos / np.linalg.norm(pos)
    return pos, fwd, np.cross(fwd, up), up


def sky_uniform(jd, right, up, fwd):
    """Same math as SkyFrame: sky attached to the camera, slow drift about the vertical axis. Column-major for GLSL."""
    l, tilt = radians(VIEW_L_DEG), radians(BAND_TILT_DEG)
    forward_c = TO_GALACTIC.T @ np.array([cos(l), sin(l), 0.0])
    pole = TO_GALACTIC.T @ np.array([0.0, 0.0, 1.0])
    up_c = cos(tilt) * pole + sin(tilt) * np.cross(pole, forward_c)
    base = np.column_stack([np.cross(forward_c, up_c), up_c, forward_c])
    alpha = ((jd - 2451545.0) * 86400.0 % DRIFT_PERIOD_S) / DRIFT_PERIOD_S * 2 * np.pi
    c, s_ = cos(alpha), sin(alpha)
    drift = np.array([[c, 0, s_], [0, 1, 0], [-s_, 0, c]])
    return (base @ drift @ np.stack([right, up, fwd])).T


def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def atmosphere_textures(ctx):
    """The scattering tables of make_atmosphere.py on units 10..13 (transmittance 2D, Rayleigh, Mie, multiple scattering 3D)."""
    t = np.frombuffer((ASSETS / "atmosphere_transmittance.bin").read_bytes(), "<f2").reshape(64, 256, 4)
    tex = [ctx.texture((256, 64), 4, t.tobytes(), dtype="f2")]
    for name in ("r", "m", "ms"):
        raw = np.frombuffer((ASSETS / f"atmosphere_scatter_{name}.bin").read_bytes(), "<u4").reshape(32, 128, 256)
        channels = [((raw & 0x7FF) << 4), ((raw >> 11) & 0x7FF) << 4, ((raw >> 22) & 0x3FF) << 5]
        rgb = np.stack([c.astype(np.uint16).view(np.float16).astype(np.float32) for c in channels], -1)
        t3 = ctx.texture3d((256, 128, 32), 3, rgb.tobytes(), dtype="f4")
        t3.repeat_x = t3.repeat_y = t3.repeat_z = False
        tex.append(t3)
    for i, t_ in enumerate(tex):
        t_.filter = (moderngl.LINEAR, moderngl.LINEAR)
        t_.use(10 + i)
    tex[0].repeat_x = tex[0].repeat_y = False
    return tex


def texture(ctx, name, srgb, unit):
    a = np.asarray(Image.open(ROOT / "build" / name).convert("RGBA"), dtype=np.float32) / 255.0
    if srgb:
        a[..., :3] = srgb_to_linear(a[..., :3])
    t = ctx.texture((a.shape[1], a.shape[0]), 4, a.tobytes(), dtype="f4")
    t.build_mipmaps()
    t.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
    t.repeat_x, t.repeat_y = True, False
    t.anisotropy = 8.0
    t.use(unit)
    return t


def main():
    when = datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00")) if len(sys.argv) > 1 else datetime.now(timezone.utc)
    out = sys.argv[2] if len(sys.argv) > 2 else str(ROOT / "build" / "preview.png")
    jd = when.timestamp() / 86400 + 2440587.5
    g = gmst(jd)
    sun, moon = earth_fixed(sun_eq(jd), g), earth_fixed(moon_eq(jd), g)
    phase = ((1 + -float(sun @ moon)) * 0.5) ** 2.5

    tan_y = tan(radians(FOV_Y_DEG) / 2)
    tan_x = tan_y * W / H
    dist = 1.0 / np.sin(atan(FILL_X * tan_x))
    cam_pos, cam_fwd, cam_right, cam_up = orbit_pose(jd, dist)

    season = {12: '01', 1: '01', 2: '01', 3: '04', 4: '04', 5: '04', 6: '07', 7: '07', 8: '07', 9: '10', 10: '10', 11: '10'}[when.month]
    ctx = moderngl.create_standalone_context()
    vs = (ASSETS / "earth.vert").read_text().replace("#version 300 es", "#version 330 core")
    fs = expand(Path(os.environ.get("SHADER", ASSETS / "earth.frag")).read_text()).replace("#version 300 es", "#version 330 core\n#define PROFILE")
    if os.environ.get("NO_CATALOG"):
        fs = fs.replace("const float STAR_PEAK_GAIN = 2.6 *", "const float STAR_PEAK_GAIN = 0.0 *")
    prog = ctx.program(vertex_shader=vs, fragment_shader=fs)
    keep = [texture(ctx, f"earth_day_{season}.png", True, 0), texture(ctx, "earth_night.png", True, 1),
            texture(ctx, "earth_topography_spec.png", False, 2), texture(ctx, os.environ.get("CLOUDS_PNG", "earth_clouds.png"), False, 3), texture(ctx, "moon.png", True, 8)]
    for i, n in enumerate(["uDay", "uNight", "uTopo", "uClouds"]):
        prog[n].value = i
    prog["uMoon"].value = 8
    for i, n in enumerate(["uTransmittance", "uScatR", "uScatM", "uScatMS"]):
        if n in prog:
            prog[n].value = 10 + i
    keep += atmosphere_textures(ctx)
    prog["uCamPos"].value = tuple(cam_pos)
    prog["uCamFwd"].value = tuple(cam_fwd)
    prog["uCamUp"].value = tuple(cam_up)
    prog["uCamRight"].value = tuple(cam_right)
    data = (ASSETS / "sky_stars.bin").read_bytes()
    count, cw, ch = struct.unpack("<3I", data[4:16])
    cells_end = 16 + cw * ch * 8
    rows = (count + 2047) // 2048
    stars_end = cells_end + rows * 2048 * 8
    cells = ctx.texture((cw, ch), 2, data[16:cells_end], dtype="u4")
    stars = ctx.texture((2048, rows), 2, data[cells_end:stars_end], dtype="u4")
    colors = ctx.texture((256, 1), 4, data[stars_end:stars_end + 1024], dtype="f1")
    for t_, unit in ((cells, 4), (stars, 5), (colors, 7)):
        t_.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t_.use(unit)
    mw = np.asarray(Image.open(ROOT / "build" / os.environ.get("MW_PNG", "sky_milkyway.png")).convert("RGBA"), dtype=np.float32) / 255.0
    milky = ctx.texture((mw.shape[1], mw.shape[0]), 4, mw.tobytes(), dtype="f4")
    milky.build_mipmaps()
    milky.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
    milky.use(6)
    keep += [cells, stars, milky, colors]
    for n, u in (("uSkyCells", 4), ("uSkyStars", 5), ("uMilky", 6), ("uStarColors", 7)):
        prog[n].value = u
    prog["uSkyRot"].write(np.array(sky_uniform(jd, cam_right, cam_up, cam_fwd), "f4").tobytes())
    if "uSkip" in prog:
        prog["uSkip"].value = int(os.environ.get("SKIP", 0))
    if "uAuroraPhase" in prog:
        prog["uAuroraPhase"].value = float(os.environ.get("AURORA_PHASE", 0.0))
    if "uStarGain" in prog:
        prog["uStarGain"].value = 1.0
    prog["uCloudShift"].value = float(os.environ.get("CLOUD_SHIFT", 0.0))
    prog["uTime"].value = float(when.timestamp() % 3600)
    prog["uPixelAngle"].value = 2 * tan_x / W
    prog["uTanHalf"].value = (tan_x, tan_y)
    prog["uSunDir"].value = tuple(sun)
    prog["uMoonDir"].value = tuple(moon)
    cos_limit = cos(radians(2.2))
    if "uSolarEclipse" in prog:
        prog["uSolarEclipse"].value = 1.0 if float(sun @ moon) > cos_limit else 0.0
    if "uLunarEclipse" in prog:
        prog["uLunarEclipse"].value = 1.0 if float(-sun @ moon) > cos_limit else 0.0
    if "uEclipticPole" in prog:  # RA 18 h, declination 90 degrees - obliquity
        prog["uEclipticPole"].value = tuple(earth_fixed((270.0, 90.0 - 23.4393), g))
    prog["uMoonPos"].value = tuple(moon * moon_distance(jd))
    prog["uMoonPhase"].value = phase
    prog["uP3"].value = 0.0
    prog["uDither"].value = 1 / 255
    fbo = ctx.simple_framebuffer((W, H), components=4)
    fbo.use()
    ctx.vertex_array(prog, []).render(moderngl.TRIANGLES, vertices=3)
    img = Image.frombytes("RGBA", (W, H), fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM)
    img.convert("RGB").save(out)
    print(f"{when.isoformat()} sun={np.round(sun, 3)} moon={np.round(moon, 3)} moonPhase={phase:.3f} -> {out}")


if __name__ == "__main__":
    main()
