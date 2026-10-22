# tools

Offline helpers; none of this ships in the APK.

- `make_textures.py` builds `app/src/main/assets/*.ktx` (ASTC 4x4, full mip chain) from `src/`. Needs Pillow, numpy and `bin/astcenc-avx2.exe` (astcenc 5.7.0 windows-x64 release from github.com/ARM-software/astc-encoder, not in this repo: put the exe into `tools/bin/`).
- `make_sky.py` builds `sky_stars.bin` (about 870,000 AT-HYG stars to magnitude 11 in a 0.25 degree cell grid, compact 8 byte records) and `sky_milkyway.ktx` (the NASA 8k map with its stars removed: only the soft band and dust lanes). Needs OpenEXR and scipy as well.
- `preview.py [ISO time] [out.png] [parallax degrees]` renders the real `earth.frag` on the desktop GPU (pip install moderngl) at 1280x2856.

## Sources (put into `src/`)

| file | source |
| --- | --- |
| `day_01..12.jpg` | NASA Blue Marble Next Generation with topography and bathymetry, all twelve monthly composites of 2004 (the app shows the current month): eoimages.gsfc.nasa.gov/images/imagerecords/73000/{73580,73605,73630,73655,73701,73726,73751,73776,73801,73826,73884,73909}/world.topo.bathy.2004MM.3x5400x2700.jpg |
| `night.jpg` | NASA Earth at Night (Black Marble 2012): eoimages.gsfc.nasa.gov/images/imagerecords/79000/79765/dnb_land_ocean_ice.2012.3600x1800.jpg |
| `elev.png` | NASA/GEBCO 8-bit elevation: eoimages.gsfc.nasa.gov/images/imagerecords/73000/73934/gebco_08_rev_elev_21600x10800.png |
| `moon_2k.jpg` | NASA SVS 4720 "Moon CGI Kit", https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_2k.jpg (LRO colour map, equirectangular, longitude 0 in the middle; NASA imagery is public domain) |
| `clouds_static_8k.jpg` | 8192x4096 visible-light cloud map, downloaded from https://www.solarsystemscope.com/textures/download/8k_earth_clouds.jpg (Solar System Scope textures, NASA-based; check their licence/attribution terms before redistributing). Built into `earth_clouds.ktx` by `make_textures.py clouds` |
| `athyg_m11.csv.gz` | AT-HYG v3.2, subset of stars brighter than magnitude 11 (Hipparcos, Tycho-2, Gaia), CC BY-SA 4.0: raw.githubusercontent.com/astronexus/ATHYG-Database/main/data/subsets/athyg_32_reduced_m11.csv.gz |
| `starmap_8k.exr` | NASA SVS Deep Star Maps 2020 (Gaia, Hipparcos, Tycho-2), public domain: svs.gsfc.nasa.gov/vis/a000000/a004800/a004851/starmap_2020_8k.exr (equirectangular, RA grows to the left, RA 0 in the middle) |

The ocean specular mask is derived from `elev.png` (elevation exactly 0) minus bright sea ice from the day map.
Tunables: `METERS_PER_ELEV_UNIT`, `RELIEF_EXAGGERATION`.

Stars are drawn individually from the catalog (position at epoch 2026.0, colour from B-V). The NASA map only supplies the
diffuse Milky Way: point sources are removed with a grey opening so they are not drawn twice, and because at the phone's
scale one map texel covers about four screen pixels, so its own stars would be blurry blobs.

## PC version (`web/`, Wallpaper Engine)

`make_hires_web.py OUT_DIR [months]` builds the 8192 x 4096 day and relief maps of the PC version (`web/assets_hi`; its night map is not used, see below) from the larger NASA files in `tools/src_hires` (not in the repo): `world.topo.bathy.2004MM.3x21600x10800.jpg` from https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/base-topography-bathymetry and `BlackMarble_2016_3km_gray.jpg` from https://science.nasa.gov/earth/earth-observatory/earth-at-night/maps; the relief uses `src/elev.png`. The experimental night map (not used: the 2016 lights are smaller than a screen pixel and looked weaker than the 2012 map at 1440p and 4K) is shaped by `NIGHT_GAIN`, `NIGHT_GAMMA`, `NIGHT_HALO`, `NIGHT_SPREAD`, `NIGHT_LIFT` and `NIGHT_LIFT_EXP` (the 2016 map holds only the lights and has none of the faint base glow of the 2012 map; small lights are also smaller than a screen pixel and fade. `NIGHT_LIFT` brightens only the faint lights, `NIGHT_SPREAD` grows each light by that many texels, `NIGHT_HALO` adds a soft glow around them; the build used `NIGHT_HALO=0.08 NIGHT_SPREAD=1 NIGHT_LIFT=0.4 NIGHT_LIFT_EXP=0.3`, which gives about the brightness of the 2012 map over the USA with sharp lights).

`make_web.py` builds `web/` from the Android assets: it copies the shaders and the atmosphere/star tables and re-encodes the textures as JPEG/WebP (ASTC does not exist on desktop GPUs; run `PNG_ONLY=1 python make_sky.py mw` once for the 8k Milky Way). `web/` is a plain WebGL2 page: `astro.js` (Sun, Moon, orbit and sky frame, the same math as the Kotlin code) and `main.js` (the renderer: earth.frag with `HDR_OUT` into an R11G11B10F target, then resolve.frag). The camera keeps the phone's distance, so perspective, atmosphere and star density match; the Earth fills 90 % of the screen height. Copy the folder to `Wallpaper Engine\projects\myprojects\earth-live-pc` (or open `web/project.json` in the editor). Properties: frame rate (30), render scale, Earth size. `python tools/web_server.py` serves it for the browser (`?t=2026-10-20T02:05:00Z` freezes the clock, `&w=2560&h=1440&capture=1` posts a screenshot to `tools/build/web_capture.png`).

## Scripts (`tools/scripts/`, run from the project folder)

Throwaway measurement helpers: `realtime.py` (frame cost per render mode over adb), `energy.py` (battery current per feature), `cap.py` (device screenshots), `cmp.py` (pixel diff of two shaders), `*_scan.py` and `find_t.py` (search the preview for moments with a feature in view), `table.py` (format PROFILE logcat lines). They expect `tools/preview.py` and `tools/build/` relative to the project folder.

## Profiling and A/B tests (debug builds only)

The debug build (`BuildConfig.DEBUG`; R8 and the shader's `#ifdef PROFILE` remove all of it from release builds) reads control files from the app's files directory (`adb shell "run-as com.example.earthlive sh -c 'echo 1 > files/<name>'"`):

| file | effect |
|---|---|
| `profile.flag` | once: times every feature on four scenes (busy loop, variants in random order); results in logcat, tag `EarthWallpaper`, lines `PROFILE |` |
| `skip_mask` | integer, bits of `SKIP_*` in `earth.frag` switch parts of the picture off (for measuring battery current) |
| `fixed_time` | epoch ms: freezes the scene's clock, so screenshots of two builds can be compared pixel by pixel |
| `no_hdr` / `no_sprites` | restart needed: draw straight to the screen / draw the stars per pixel in `earth.frag` instead of as sprites |

Restart the wallpaper with `adb install -r`, never with `am force-stop`: that resets the system wallpaper to the default.

## Atmosphere tables

`make_atmosphere.py` precomputes the scattering of the atmosphere (Rayleigh, Mie, ozone, multiple scattering; real Earth values, top of the air at 100 km) into `assets/atmosphere_*.bin` (12 MB, numpy only, about 20 s). `assets/atmosphere.glsl` reads them in the shaders. If you change a parameter in the Python file, the constants at the top of `atmosphere.glsl` (top radius, sun angular radius, Mie g) must match. `preview.py` loads the same files.
