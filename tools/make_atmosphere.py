"""Precomputes the atmosphere's light scattering into lookup tables (app/src/main/assets/atmosphere_*.bin).

The method follows Bruneton & Neyret (2008, reimplemented 2017): the light scattered towards a viewer is tabulated
for every view ray (height, view zenith, sun zenith, view-sun angle), so that the app reads it instead of marching
through the air for every pixel. Parameters are the real ones for Earth (units: Earth radii, top of the air at 100 km):
Rayleigh scattering (scale height 8 km), Mie scattering by haze (1.2 km, g = 0.8) and the ozone layer (absorbs red and
green, which is what turns the thin edge of the atmosphere deep blue; tent profile around 25 km).

Tables (see assets/atmosphere.glsl for the lookup and the coordinate mapping, which is the inverse of the one here):
  atmosphere_transmittance.bin   256 x 64 RGBA half float: sunlight reaching the top of the air from (height, zenith)
  atmosphere_scatter_r.bin       Rayleigh single scattering, R11G11B10F, 3D texture 256 x 128 x 32 (nu*mu_s, mu, r)
  atmosphere_scatter_m.bin       Mie single scattering, same layout
  atmosphere_scatter_ms.bin      multiple scattering as an isotropic term (Hillaire 2020), same layout
Run: python make_atmosphere.py   (numpy only, a few minutes)
"""
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT.parent / "app" / "src" / "main" / "assets"

EARTH_KM = 6371.0
RG = 1.0
RT = 1.0 + 100.0 / EARTH_KM
H = np.sqrt(RT * RT - RG * RG)  # distance to the horizon from the top of the air
HR = 8.0 / EARTH_KM
HM = 1.2 / EARTH_KM
BETA_R = np.array([5.802e-3, 13.558e-3, 33.1e-3]) * EARTH_KM      # per Earth radius at sea level, 680 / 550 / 440 nm
BETA_M_S = 3.996e-3 * EARTH_KM
BETA_M_E = 4.44e-3 * EARTH_KM
BETA_O = np.array([0.650e-3, 1.881e-3, 0.085e-3]) * EARTH_KM
SUN_ANGULAR_RADIUS = 0.004675
MU_S_MIN = np.cos(np.radians(102.0))
GROUND_ALBEDO = 0.25

NU, MUS, MU, R = 8, 32, 128, 32      # scattering table sizes
TW, TH = 256, 64                      # transmittance table
MSN = 32                              # multiple scattering table (height x sun zenith)
STEPS = 40


def dens_r(h): return np.exp(-h / HR)
def dens_m(h): return np.exp(-h / HM)
def dens_o(h): return np.maximum(0.0, 1.0 - np.abs(h * EARTH_KM - 25.0) / 15.0)


def extinction(h):
    return BETA_R * dens_r(h)[..., None] + BETA_M_E * dens_m(h)[..., None] + BETA_O * dens_o(h)[..., None]


def unit_to_tex(x, size): return 0.5 / size + x * (1.0 - 1.0 / size)
def tex_to_unit(u, size): return (u - 0.5 / size) / (1.0 - 1.0 / size)


def dist_top(r, mu):
    return np.maximum(-r * mu + np.sqrt(np.maximum(r * r * (mu * mu - 1.0) + RT * RT, 0.0)), 0.0)


def dist_bottom(r, mu):
    return np.maximum(-r * mu - np.sqrt(np.maximum(r * r * (mu * mu - 1.0) + RG * RG, 0.0)), 0.0)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def bilerp(table, fx, fy):
    """table[ix, iy, ...] sampled at fractional indices (fx, fy), clamped."""
    fx = np.clip(fx, 0, table.shape[0] - 1)
    fy = np.clip(fy, 0, table.shape[1] - 1)
    x0 = np.minimum(np.floor(fx).astype(int), table.shape[0] - 2)
    y0 = np.minimum(np.floor(fy).astype(int), table.shape[1] - 2)
    tx = (fx - x0)[..., None]
    ty = (fy - y0)[..., None]
    return (table[x0, y0] * (1 - tx) * (1 - ty) + table[x0 + 1, y0] * tx * (1 - ty)
            + table[x0, y0 + 1] * (1 - tx) * ty + table[x0 + 1, y0 + 1] * tx * ty)


# ---- transmittance ----------------------------------------------------------------------------------------------------

def build_transmittance():
    xm = np.arange(TW) / (TW - 1)
    xr = np.arange(TH) / (TH - 1)
    XM, XR = np.meshgrid(xm, xr, indexing="ij")
    rho = H * XR
    r = np.sqrt(rho * rho + RG * RG)
    d_min, d_max = RT - r, rho + H
    d = d_min + XM * (d_max - d_min)
    mu = np.where(d == 0, 1.0, np.clip((H * H - rho * rho - d * d) / (2 * r * np.where(d == 0, 1.0, d)), -1, 1))
    length = dist_top(r, mu)
    n = 500
    dd = length / n
    tau = np.zeros(r.shape + (3,))
    for i in range(n):
        s = (i + 0.5) * dd
        rs = np.sqrt(s * s + 2 * r * mu * s + r * r)
        tau += extinction(rs - RG) * dd[..., None]
    return np.exp(-tau)  # [i_mu, i_r, 3]


T_LUT = None


def lut_t_top(r, mu):
    rho = np.sqrt(np.maximum(r * r - RG * RG, 0.0))
    d = dist_top(r, mu)
    d_min, d_max = RT - r, rho + H
    xm = (d - d_min) / (d_max - d_min)
    xr = rho / H
    return bilerp(T_LUT, xm * (TW - 1), xr * (TH - 1))


def sun_t(r, mu_s):
    sin_h = RG / r
    cos_h = -np.sqrt(np.maximum(1.0 - sin_h * sin_h, 0.0))
    return lut_t_top(r, mu_s) * smoothstep(-sin_h * SUN_ANGULAR_RADIUS, sin_h * SUN_ANGULAR_RADIUS, mu_s - cos_h)[..., None]


# ---- multiple scattering ----------------------------------------------------------------------------------------------

def build_multiscattering():
    """Psi_ms(height, sun zenith): isotropic multiple scattering per unit scattering coefficient (Hillaire 2020)."""
    n_dirs, n_steps = 64, 20
    i = np.arange(n_dirs)
    z = 1 - 2 * (i + 0.5) / n_dirs
    phi = i * np.pi * (3 - np.sqrt(5))
    omega = np.stack([np.sqrt(1 - z * z) * np.cos(phi), np.sqrt(1 - z * z) * np.sin(phi), z], -1)  # [dirs, 3]
    psi = np.zeros((MSN, MSN, 3))  # [i_h, i_mu_s]
    for a in range(MSN):
        h = (a + 0.5) / MSN * (RT - RG)
        r = RG + h
        for b in range(MSN):
            mu_s = 2 * (b + 0.5) / MSN - 1
            sun = np.array([np.sqrt(1 - mu_s * mu_s), 0.0, mu_s])
            mu = omega[:, 2]
            nu = omega @ sun
            ground = (mu < 0) & (r * r * (mu * mu - 1) + RG * RG >= 0)
            d_end = np.where(ground, dist_bottom(r, mu), dist_top(r, mu))
            dd = d_end / n_steps
            tau = np.zeros((n_dirs, 3))
            lum = np.zeros((n_dirs, 3))
            transfer = np.zeros((n_dirs, 3))
            for k in range(n_steps):
                d = (k + 0.5) * dd
                ri = np.clip(np.sqrt(d * d + 2 * r * mu * d + r * r), RG, RT)
                musi = np.clip((r * mu_s + d * nu) / ri, -1, 1)
                hi = ri - RG
                ext = extinction(hi)
                t_cam = np.exp(-(tau + 0.5 * ext * dd[:, None]))
                sigma_s = BETA_R * dens_r(hi)[:, None] + BETA_M_S * dens_m(hi)[:, None]
                lum += sigma_s * t_cam * sun_t(ri, musi) * dd[:, None] / (4 * np.pi)
                transfer += sigma_s * t_cam * dd[:, None]
                tau += ext * dd[:, None]
            # light bounced by the ground
            r_g = RG
            d = d_end
            mu_g = np.clip((r * mu_s + d * nu) / r_g, -1, 1)
            t_cam = np.exp(-tau)
            lum += np.where(ground[:, None], t_cam * sun_t(np.full(n_dirs, RG), mu_g) * np.maximum(mu_g, 0)[:, None] * GROUND_ALBEDO / np.pi, 0.0)
            l2 = lum.mean(0)
            f = transfer.mean(0)
            psi[a, b] = l2 / (1.0 - f)
    return psi


PSI = None


def psi_lookup(h, mu_s):
    fy = h / (RT - RG) * MSN - 0.5
    fx = (mu_s + 1) / 2 * MSN - 0.5
    return bilerp(PSI, fy, fx)  # PSI[i_h, i_mu_s]


# ---- scattering tables ------------------------------------------------------------------------------------------------

def inverse_mapping(i_r, i_mu, i_mus, i_nu):
    """Texel indices -> (r, mu, mu_s, nu, ray hits the ground), the inverse of the mapping in atmosphere.glsl."""
    u_r = (i_r + 0.5) / R
    u_mu = (i_mu + 0.5) / MU
    u_mus = (i_mus + 0.5) / MUS
    x_r = tex_to_unit(u_r, R)
    rho = H * x_r
    r = np.sqrt(rho * rho + RG * RG)
    ground = u_mu < 0.5
    # rays that hit the ground
    d_min_g = r - RG
    d_max_g = rho
    d_g = d_min_g + (d_max_g - d_min_g) * tex_to_unit(1 - 2 * u_mu, MU / 2)
    mu_g = np.where(d_g == 0, -1.0, np.clip(-(rho * rho + d_g * d_g) / (2 * r * np.where(d_g == 0, 1.0, d_g)), -1, 1))
    # rays that do not
    d_min_t = RT - r
    d_max_t = rho + H
    d_t = d_min_t + (d_max_t - d_min_t) * tex_to_unit(2 * u_mu - 1, MU / 2)
    mu_t = np.where(d_t == 0, 1.0, np.clip((H * H - rho * rho - d_t * d_t) / (2 * r * np.where(d_t == 0, 1.0, d_t)), -1, 1))
    mu = np.where(ground, mu_g, mu_t)
    x_mus = tex_to_unit(u_mus, MUS)
    d_min, d_max = RT - RG, H
    big_d = dist_top(RG, MU_S_MIN)
    big_a = (big_d - d_min) / (d_max - d_min)
    a = (big_a - x_mus * big_a) / (1.0 + x_mus * big_a)
    d = d_min + np.minimum(a, big_a) * (d_max - d_min)
    mu_s = np.where(d == 0, 1.0, np.clip((H * H - d * d) / (2 * RG * np.where(d == 0, 1.0, d)), -1, 1))
    nu = np.clip(i_nu / (NU - 1) * 2 - 1, -1, 1)
    lim = np.sqrt(np.maximum((1 - mu * mu) * (1 - mu_s * mu_s), 0.0))
    nu = np.clip(nu, mu * mu_s - lim, mu * mu_s + lim)
    return r, mu, mu_s, nu, ground


def build_scattering():
    out = [np.zeros((R, MU, MUS, NU, 3), np.float32) for _ in range(3)]
    i_mu, i_mus, i_nu = np.meshgrid(np.arange(MU), np.arange(MUS), np.arange(NU), indexing="ij")
    for i_r in range(R):
        t0 = time.time()
        r, mu, mu_s, nu, ground = inverse_mapping(np.full(i_mu.shape, i_r), i_mu, i_mus, i_nu)
        r, mu, mu_s, nu, ground = (a.reshape(-1) for a in (r, mu, mu_s, nu, ground))
        d_end = np.where(ground, dist_bottom(r, mu), dist_top(r, mu))
        dd = (d_end / STEPS)[:, None]
        tau = np.zeros((r.size, 3))
        sum_r = np.zeros((r.size, 3))
        sum_m = np.zeros((r.size, 3))
        sum_ms = np.zeros((r.size, 3))
        for k in range(STEPS):
            d = (k + 0.5) * dd[:, 0]
            ri = np.clip(np.sqrt(d * d + 2 * r * mu * d + r * r), RG, RT)
            musi = np.clip((r * mu_s + d * nu) / ri, -1, 1)
            hi = ri - RG
            ext = extinction(hi)
            t_cam = np.exp(-(tau + 0.5 * ext * dd))
            w = t_cam * sun_t(ri, musi) * dd
            sum_r += dens_r(hi)[:, None] * w
            sum_m += dens_m(hi)[:, None] * w
            sigma_s = BETA_R * dens_r(hi)[:, None] + BETA_M_S * dens_m(hi)[:, None]
            sum_ms += sigma_s * psi_lookup(hi, musi) * t_cam * dd
            tau += ext * dd
        shape = (MU, MUS, NU, 3)
        out[0][i_r] = (sum_r * BETA_R).reshape(shape)
        out[1][i_r] = (sum_m * BETA_M_S).reshape(shape)
        out[2][i_r] = sum_ms.reshape(shape)
        print(f"  r layer {i_r + 1}/{R}  {time.time() - t0:.1f} s", flush=True)
    return out


def pack_r11g11b10(rgb):
    """float32 (..., 3), non-negative -> uint32 R11G11B10F (GL_UNSIGNED_INT_10F_11F_11F_REV)."""
    half = np.clip(rgb, 0, 60000).astype(np.float16).view(np.uint16).astype(np.uint32)
    r = np.minimum((half[..., 0] + 8) >> 4, 0x7BF)
    g = np.minimum((half[..., 1] + 8) >> 4, 0x7BF)
    b = np.minimum((half[..., 2] + 16) >> 5, 0x3DF)
    return (r | (g << 11) | (b << 22)).astype("<u4")


def main():
    global T_LUT, PSI
    t = time.time()
    print("transmittance ...", flush=True)
    T_LUT = build_transmittance()
    zenith = T_LUT[0, 0]
    print("  zenith transmittance (r, g, b):", np.round(zenith, 3), "(expect about 0.88 / 0.87 / 0.78; the horizon value is T_LUT[-1, 0])")
    print("multiple scattering ...", flush=True)
    PSI = build_multiscattering()
    print("  Psi_ms at the ground, sun at the zenith:", np.round(PSI[0, MSN - 1], 4))
    print("scattering ...", flush=True)
    tables = build_scattering()
    ASSETS.mkdir(parents=True, exist_ok=True)
    rgba = np.concatenate([np.transpose(T_LUT, (1, 0, 2)), np.ones((TH, TW, 1))], -1).astype("<f2")  # rows = r, columns = mu
    (ASSETS / "atmosphere_transmittance.bin").write_bytes(rgba.tobytes())
    for name, table in zip(("r", "m", "ms"), tables):
        packed = pack_r11g11b10(np.transpose(table, (0, 1, 3, 2, 4)).reshape(R, MU, NU * MUS, 3))  # [r, mu, nu * MUS + mu_s]
        (ASSETS / f"atmosphere_scatter_{name}.bin").write_bytes(packed.tobytes())
        print(f"atmosphere_scatter_{name}.bin  max {table.max():.3f}  mean {table.mean():.4f}")
    print(f"done in {time.time() - t:.0f} s")


if __name__ == "__main__":
    sys.exit(main())
