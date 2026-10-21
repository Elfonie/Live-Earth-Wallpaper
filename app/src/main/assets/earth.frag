#version 300 es
precision highp float;
precision highp int; // the default for int is mediump: 16 bit on some GPUs, which broke star indices above 32767
precision highp sampler2D;
precision highp usampler2D;
precision highp sampler3D;

// Analytic ray cast of three concentric spheres (ground, cloud shell, atmosphere) in the Earth-fixed frame:
// x = (0 N, 0 E), y = north pole, z = (0 N, 90 W). All lighting is computed in linear sRGB, then ACES + OETF.
// Space behind the globe shows the real sky in J2000 coordinates: about 870,000 catalog stars (AT-HYG, to magnitude 11)
// drawn crisply, in front of NASA's diffuse Milky Way with its dust lanes.

in vec2 vNdc;
out vec4 outColor;

uniform vec3 uCamPos;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform vec3 uCamFwd;
uniform vec2 uTanHalf;
uniform vec3 uSunDir;    // unit vector from Earth's center to the Sun
uniform vec3 uMoonPos;   // the Moon's centre from the Earth's centre, in Earth radii
uniform sampler2D uMoon; // LRO colour map, longitude 0 in the middle
uniform vec3 uMoonDir;   // unit vector from Earth's center to the Moon
uniform float uMoonPhase; // 0 new .. 1 full
uniform float uP3;       // 1.0: encode for Display P3, 0.0: sRGB
uniform float uDither;   // 1 / (2^bits - 1)
uniform sampler2D uDay;   // sRGB
uniform sampler2D uNight; // sRGB
uniform sampler2D uTopo;  // rgb = tangent-space normal (x east, y north, z up), a = ocean specular mask
uniform sampler2D uClouds; // r = cloud coverage
uniform mat3 uSkyRot;      // Earth-fixed ray -> J2000 equatorial direction (inverse precession and GMST rotation)
uniform float uSolarEclipse; // 1 when the Moon is close enough to the Sun's direction to shade the Earth
uniform float uLunarEclipse; // 1 when the Moon is close enough to the anti-Sun direction to enter the Earth's shadow
uniform vec3 uEclipticPole;  // north pole of the ecliptic in the Earth-fixed frame (for the zodiacal light)
uniform int uSkip;          // bits that switch parts of the picture off (0 = everything on), see SKIP_*: the user's settings, and profiling
uniform float uSkyBlur;   // radians the camera turned about the north axis during one frame (0: no motion blur of the Sun and the Moon)
uniform float uStarGain;   // the user's star brightness, 1 = as drawn by default
uniform float uFlashRate;  // the user's lightning frequency, 1 = as drawn by default
uniform float uAuroraPhase; // 0..1 around every 2 minutes; the aurora only uses periodic functions of it, so it never jumps
uniform float uCloudShift; // the cloud layer's eastward drift against the ground, in turns (0..1)
uniform float uTime;       // seconds, wrapped; only drives the star flicker
uniform float uPixelAngle; // radians per pixel at the screen centre
uniform highp usampler2D uSkyCells; // per 0.25x0.25 degree cell: first star index, star count
uniform highp usampler2D uSkyStars; // per star: position fractions within the cell, magnitude, colour index, seed
uniform sampler2D uStarColors;      // 256 display colours indexed by B-V
uniform sampler2D uMilky;           // gamma 2.2 encoded diffuse Milky Way

#include "sky_common.glsl"
#include "atmosphere.glsl"


const float SUN_E = 3.2;
const float ATMO_BOOST = 0.8; // the ground is shaded as albedo * E (no 1/pi): the same scale for the scattered light
const vec3 MOON_COLOR = vec3(0.06, 0.085, 0.13) * 0.0537; // moonlight: 1 : 700 of the Sun (the real ratio is 1 : 400 000)
const vec3 AIRGLOW = vec3(0.006, 0.008, 0.014) * 0.2; // the faint light of the night sky on the dark side
const vec3 SKY_AMBIENT = vec3(0.5, 0.65, 1.0) * 0.07;


// Cloud relief: thick cloud facing the Sun is brighter, the lee side darker (the map's density read as a height field)
// Culling thresholds on a coarse (3-4x blurrier) look at the texture: below them the fine samples would find nothing visible
const float LIGHTS_COARSE_MIN = 0.0002;
const float CLOUDS_COARSE_MIN = 0.012;
const float DETAIL_MIN = 0.06;     // fine and coarse sample closer than this: nothing finer than a pixel to anti-alias
const float RELIEF_PX = 2.5;      // how far toward the Sun the density is compared, in screen pixels
const float RELIEF_GAIN = 4.0;
const float RELIEF_STRENGTH = 0.9;

// Aurora: an irregular oval around the geomagnetic poles (80.7 N, 72.7 W and its antipode). The emission is field-aligned
// (it depends on the place, not on the height) and a vertical profile gives the violet lower fringe, the green body and
// the faint red top; four thin shells along the view ray add them up, so seen along the limb the curtains stand up.
const vec3 MAG_POLE = vec3(0.0479, 0.9870, 0.1536); // renderer frame: x = 0N 0E, y = north, z = 0N 90W
const float AURORA_COLATITUDE = 0.33;               // 19 degrees from the pole at magnetic noon, 3 degrees more at midnight
const float AURORA_WIDTH = 0.05;
const float AURORA_GAIN = 0.31;
// heights 100 to 300 km at 64 % of their real height above the ground (lower), as 16 thin shells of equal spacing that
// follow a smooth profile: a sharp lower edge with a violet-pink fringe, the green body that thins out upwards (557.7 nm), a
// trace of yellow, and the faint red of atomic oxygen at the top (630 nm). Many close shells (a pixel or less apart) so that
// the layers do not show as separate stripes where the curtains are seen edge-on at the limb.
const int AURORA_N = 16;
const float AURORA_SHELL[16] = float[16](1.0100, 1.0114, 1.0127, 1.0141, 1.0154, 1.0167, 1.0181, 1.0194, 1.0208, 1.0221, 1.0234, 1.0248, 1.0261, 1.0275, 1.0288, 1.0301);
const vec3 AURORA_COLOUR[16] = vec3[16](vec3(0.2398, 0.0879, 0.3197), vec3(0.1940, 0.9446, 0.3896), vec3(0.1120, 0.4445, 0.1555), vec3(0.0673, 0.2388, 0.0827), vec3(0.0329, 0.0836, 0.0279), vec3(0.0259, 0.0623, 0.0208), vec3(0.0190, 0.0411, 0.0137), vec3(0.0120, 0.0198, 0.0067), vec3(0.0099, 0.0089, 0.0031), vec3(0.0126, 0.0081, 0.0031), vec3(0.0153, 0.0074, 0.0031), vec3(0.0180, 0.0067, 0.0032), vec3(0.0207, 0.0060, 0.0032), vec3(0.0234, 0.0053, 0.0032), vec3(0.0261, 0.0046, 0.0032), vec3(0.0288, 0.0038, 0.0032));

// Upper atmosphere seen from orbit: airglow (thin layers at 85-115 km: orange sodium/OH below, green oxygen in the middle,
// faint red above) shines all night but is only visible along the limb, where the line of sight runs through the layers
// for a long way. Noctilucent clouds (ice at 83 km) are sunlit while the ground below is already dark, so they hang as
// bluish-white veils in a band along the terminator, only at 48-75 degrees latitude of the summer hemisphere.
const float AIRGLOW_GAIN = 0.0008;
const float NLC_GAIN = 0.035;
const float NLC_LOW = 1.0128;
const float NLC_HIGH = 1.0142;
const float UPPER_AIR_TOP = 1.0211;
// The airglow layers as bell-shaped profiles in height, sampled in 17 thin shells of 0.0012 Earth radii (8 km) from 1.007: a faint
// diffuse base low down, orange OH/sodium, the green of atomic oxygen (557.7 nm) in the middle, a trace of yellow-green and the
// faint red (630 nm) above it. The colour changes smoothly with height and the glow fades into the dark below instead of
// stopping, so there is a band and not two thin lines.
const int AIRGLOW_N = 22;
const float AIRGLOW_R0 = 1.0035, AIRGLOW_STEP = 0.0008;
const vec3 AIRGLOW_PROFILE[22] = vec3[22](vec3(0.0195, 0.0280, 0.0105), vec3(0.0298, 0.0426, 0.0160), vec3(0.0421, 0.0600, 0.0226), vec3(0.0554, 0.0793, 0.0300), vec3(0.0700, 0.1026, 0.0392), vec3(0.0932, 0.1422, 0.0549), vec3(0.1561, 0.2339, 0.0893), vec3(0.3127, 0.4328, 0.1600), vec3(0.5328, 0.7312, 0.2681), vec3(0.6507, 1.0043, 0.3802), vec3(0.6198, 1.1594, 0.4596), vec3(0.5639, 1.1982, 0.4876), vec3(0.5263, 1.1133, 0.4543), vec3(0.4747, 0.9155, 0.3702), vec3(0.4054, 0.6713, 0.2677), vec3(0.3352, 0.4509, 0.1776), vec3(0.2750, 0.2866, 0.1126), vec3(0.2267, 0.1769, 0.0709), vec3(0.1882, 0.1076, 0.0456), vec3(0.1571, 0.0654, 0.0303), vec3(0.1311, 0.0408, 0.0211), vec3(0.1084, 0.0268, 0.0154));


// Lightning: flashes inside large night-side cloud decks of the tropics, on a grid of 1.5 degree cells. Every cell may
// flash once per FLASH_PERIOD seconds, with a probability that grows with the cloudiness; a flash is a soft glow of about
// 80 km that decays within a fifth of a second (two pulses), so that even 10 frames per second catch it.
const vec2 FLASH_GRID = vec2(240.0, 120.0);
const float FLASH_PERIOD = 2.0;
const float FLASH_CHANCE = 0.0006;
const float FLASH_GAIN = 1.3;
const float CITY_GLOW_GAIN = 0.9;

const float R_CIRRUS = 1.006;        // a thin high layer above the cloud shell (exaggerated like it: about 38 km)
const float CLOUD_DETAIL = 0.14;      // noise finer than the map: ragged edges and texture inside the decks

// The Sun's disc (0.2666 degrees across seen from Earth) and its glow, for when it peeks around the limb
const float SUN_RADIUS = 0.00465;
const float R_MOON = 0.2725;  // Earth radii
const float MOON_GAIN = 0.5;

const int BLUR_TAPS = 6;
const int SKIP_STARS = 1, SKIP_MILKY = 2, SKIP_ATMO_BACK = 4, SKIP_ATMO_FRONT = 8, SKIP_AURORA = 16, SKIP_SUN_MOON = 32,
          SKIP_CLOUD_TAPS = 64, SKIP_RELIEF = 128, SKIP_CLOUDS = 256, SKIP_LIGHTS = 512, SKIP_UPPER_AIR = 1024, SKIP_LIGHTNING = 2048, SKIP_CIRRUS = 4096, SKIP_SUN_BLUR = 8192;

const float OCEAN_ROUGHNESS = 0.45; // GGX alpha 0.2, a sea at about 7 m/s: a wide soft sheen instead of a hot spot
// The cloud map is infrared: cold land (autumn Europe, Russia, snow) is as bright as cloud. Over land the regional
// average above an ocean-like baseline is therefore treated as ground temperature and subtracted.
const float LAND_COLD_BASELINE = 0.45;
const float LAND_COLD_BIAS = 0.85;

// Sky. Star brightness follows 10^(-0.16 m), i.e. the flux ratio compressed by exponent 0.4: a display cannot
// reproduce the 600:1 range between mag 0 and 7, so ordering and colour are physical while contrast is compressed.
const float MW_SCALE = 0.07;   // the naked-eye Milky Way is a faint glow next to the brighter stars
const float MW_SATURATION = 0.55;
const mat3 SRGB_TO_P3 = mat3(
    0.8224621, 0.0331941, 0.0170827,
    0.1775380, 0.9668058, 0.0723974,
    0.0000000, 0.0000000, 0.9105199);

vec2 sphereUV(vec3 n) {
    return vec2(atan(-n.z, n.x) / (2.0 * PI) + 0.5, 0.5 - asin(clamp(n.y, -1.0, 1.0)) / PI);
}

// Gradients that stay valid across the u = 0/1 wrap, so no mip seam line appears. Derivatives need uniform control flow,
// so they are computed for every pixel; the (expensive) fetches that use them can then sit inside branches.
struct SeamGrad { vec2 dx; vec2 dy; };

const vec2 SUBPIXEL[4] = vec2[4](vec2(0.125, 0.375), vec2(-0.375, 0.125), vec2(-0.125, -0.375), vec2(0.375, -0.125));

SeamGrad seamGrad(vec2 uv) {
    vec2 a = vec2(fract(uv.x), uv.y);
    vec2 b = vec2(fract(uv.x + 0.5) - 0.5, uv.y);
    bool useA = fwidth(a.x) <= fwidth(b.x);
    return SeamGrad(useA ? dFdx(a) : dFdx(b), useA ? dFdy(a) : dFdy(b));
}

vec4 fetch(sampler2D t, vec2 uv, SeamGrad g) { return textureGrad(t, uv, g.dx, g.dy); }

// Cook-Torrance (GGX, Schlick Fresnel and visibility) reflected radiance per unit irradiance: D*F*G / (4 n.v)
float ggxSpec(vec3 n, vec3 l, vec3 v) {
    float nl = dot(n, l);
    if (nl <= 0.0) return 0.0;
    vec3 h = normalize(l + v);
    float nv = max(dot(n, v), 1e-3);
    float nh = max(dot(n, h), 0.0);
    float vh = max(dot(v, h), 0.0);
    float a = OCEAN_ROUGHNESS * OCEAN_ROUGHNESS;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float D = a2 / (PI * d * d);
    float F = 0.02 + 0.98 * pow(1.0 - vh, 5.0);
    float k = a * 0.5;
    float G = (nl / (nl * (1.0 - k) + k)) * (nv / (nv * (1.0 - k) + k));
    return D * F * G / (4.0 * nv);
}




// Catalog stars as Gaussian point spread functions. The cell grid has 0.25 degree cells and one pixel is about 0.011
// degrees wide, so a star's footprint (3 sigma <= 5.4 px = 0.06 deg) is smaller than half a cell: the pixel's own cell
// plus the neighbours on its nearer side (2 x 2 cells) are enough, and a neighbour only matters when the pixel lies
// within the footprint's reach of that cell border (about 2 of the 4 cells per pixel on average).
const float CELL_RAD = 0.25 * PI / 180.0;

vec3 starfield(vec3 dir, float limb) {
    float raCells = fract(atan(dir.y, dir.x) / (2.0 * PI)) * 1440.0;
    float decCells = (asin(clamp(dir.z, -1.0, 1.0)) + 0.5 * PI) / CELL_RAD;
    ivec2 cell = ivec2(min(int(raCells), 1439), clamp(int(decCells), 0, 719));
    ivec2 side = ivec2(fract(raCells) < 0.5 ? -1 : 1, fract(decCells) < 0.5 ? -1 : 1);
    vec2 inCell = vec2(fract(raCells), fract(decCells)); // the pixel's position inside its cell, in cell units
    float reach = 3.0 * (STAR_SIGMA + 0.6) * uPixelAngle / CELL_RAD; // the largest footprint (3 sigma), in cells
    vec2 toBorder = min(inCell, 1.0 - inCell); // distance to the nearer border in each direction
    float cosDec = sqrt(max(1.0 - dir.z * dir.z, 0.0));
    bool cheapTest = cosDec > 0.2; // near the celestial poles the planar test is not conservative: use the exact one
    float flickerAmp = FLICKER_BASE + FLICKER_LIMB * limb;
    vec3 sum = vec3(0.0);
    for (int n = 0; n < 4; n++) {
        ivec2 off = ivec2((n & 1) * side.x, (n >> 1) * side.y);
        if (((n & 1) != 0 && toBorder.x > reach) || ((n >> 1) != 0 && toBorder.y > reach)) continue; // too far to matter
        ivec2 c = cell + off;
        if (c.y < 0 || c.y > 719) continue;
        c.x = (c.x + 1440) % 1440;
        uvec2 entry = texelFetch(uSkyCells, c, 0).rg;
        for (uint k = 0u; k < min(entry.y, 64u); k++) {
            int i = int(entry.x + k);
            uvec2 rec = texelFetch(uSkyStars, ivec2(i & 2047, i >> 11), 0).rg;
            float mag = float(rec.y & 255u) / 16.0 - 2.0;
            if (mag > STAR_MAG_LIMIT) continue;
            float sigma = starSigma(mag);
            vec2 frac = vec2(float(rec.x & 65535u), float(rec.x >> 16)) / 65535.0;
            if (cheapTest) { // planar distance in radians, with 30 % slack for the cosine of the declination varying
                vec2 d = (vec2(off) + frac - inCell) * vec2(cosDec, 1.0);
                if (dot(d, d) * CELL_RAD * CELL_RAD > 1.7 * 9.0 * sigma * sigma * uPixelAngle * uPixelAngle) continue;
            }
            float ra = (float(c.x) + frac.x) * CELL_RAD;
            float dec = (float(c.y) + frac.y) * CELL_RAD - 0.5 * PI;
            vec3 star = vec3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec));
            // |cross| stays accurate for tiny separations, unlike acos(dot)
            float px = length(cross(star, dir)) / uPixelAngle;
            if (px > 3.0 * sigma) continue;
            vec3 tint = texelFetch(uStarColors, ivec2(int((rec.y >> 8) & 255u), 0), 0).rgb;
            float peak = starPeak(mag);
            float flicker = 1.0 + flickerAmp * twinkle(float(rec.y >> 16));
            sum += tint * peak * flicker * exp(-0.5 * px * px / (sigma * sigma));
        }
    }
    return sum;
}

vec2 equirectUV(vec3 dir) { // NASA Deep Star Map layout: RA grows to the left, RA 0 in the middle, north up
    float ra01 = fract(atan(dir.y, dir.x) / (2.0 * PI));
    return vec2(fract(0.5 - ra01), 0.5 - asin(clamp(dir.z, -1.0, 1.0)) / PI);
}



// Noise along the magnetic azimuth az (ca = cos, sin of it): sampled on a circle in the noise plane so that it has no seam where
// az wraps; freq is the number of features around the oval, off moves the circle through the noise (the time evolution).
float auroraNoise(vec2 ca, float freq, vec2 off) { return perlin(freq * 0.15915494 * ca + off); }

// The emission of the aurora per unit path at the place pn (the same at every height), 0 on the day side. An aurora is a
// few thin curtains (sheets standing on the field lines) that follow the oval in folds, with fine rays along them. pxAz is the
// size of a pixel in radians of azimuth: detail finer than a pixel is faded out, and the edges are never sharper than a pixel.
float auroraAt(vec3 pn, float pxAz, float pxColat) {
    float night = smoothstep(0.15, -0.25, dot(pn, uSunDir));
    if (night <= 0.0) return 0.0;
    vec3 u = normalize(cross(MAG_POLE, vec3(0.0, 0.0, 1.0)));
    vec3 v = cross(MAG_POLE, u);
    float az = atan(dot(pn, v), dot(pn, u));
    vec2 ca = vec2(cos(az), sin(az));
    float t = 6.2831853 * uAuroraPhase;
    // circles through the noise: every pattern drifts and reshapes during the 2 minutes, and ends where it began
    vec2 s0 = vec2(cos(t), sin(t));
    vec2 s1 = vec2(cos(t + 1.0), sin(t + 1.0));
    vec2 s2 = vec2(cos(t + 2.0), sin(t + 2.0));
    // the oval sits closer to the equator and glows brighter towards magnetic midnight (the side away from the Sun)
    float midnight = 0.5 - 0.5 * cos(az - atan(dot(uSunDir, v), dot(uSunDir, u)));
    float colat = acos(min(abs(dot(pn, MAG_POLE)), 1.0));
    // the oval is wavy; it is sharp on its equatorward side and diffuse on the poleward side
    float centre = AURORA_COLATITUDE + 0.05 * midnight + 0.05 * auroraNoise(ca, 10.0, vec2(7.0, 0.5) + 0.5 * s0);
    float off = (colat - centre) / AURORA_WIDTH;
    float envelope = off > 0.0 ? exp(-off * off * 4.0) : exp(-off * off * 0.8);
    if (envelope < 0.02) return 0.0;
    // a main curtain and a fainter second one, folded at two scales and moving; the lower (equatorward) edge is sharp
    float fade21 = 1.0 - smoothstep(0.3, 0.7, pxAz * 21.0);
    float fold1 = 0.028 * auroraNoise(ca, 7.0, vec2(2.0, 3.0) + 2.0 * s0) + 0.007 * fade21 * auroraNoise(ca, 21.0, vec2(-3.0, 7.0) + 3.0 * s1);
    float fold2 = 0.015 * auroraNoise(ca, 5.0, vec2(9.0, 11.0) + 1.5 * s2);
    float w1 = max(0.0105, 1.2 * pxColat), w2 = max(0.0135, 1.2 * pxColat);
    float r1 = (colat - centre - 0.012 + fold1) / w1;
    float r2 = (colat - centre + 0.020 + fold2) / w2;
    float curtains = exp(-r1 * r1 * (r1 > 0.0 ? 1.9 : 0.65)) + 0.5 * exp(-r2 * r2 * (r2 > 0.0 ? 1.9 : 0.65));
    // bright stretches and dim ones, brightness waves running along the oval, and rays along the curtains that shimmer
    float patches = 0.15 + 0.85 * smoothstep(-0.2, 0.4, auroraNoise(ca, 15.0, vec2(3.0, 1.3) + 0.8 * s0));
    float pulse = 0.82 + 0.18 * sin(8.0 * t - 5.0 * az);
    float fade70 = 1.0 - smoothstep(0.3, 0.7, pxAz * 70.0);
    float rays = clamp(0.55 + 0.9 * (0.9 * auroraNoise(ca, 32.0, vec2(0.0, 5.0) + 3.0 * s2) + 0.8 * fade70 * auroraNoise(ca, 70.0, vec2(3.0, 0.0) + 5.0 * s1)), 0.06, 1.35);
    float sector = 0.35 + 0.65 * midnight * midnight;
    return envelope * patches * pulse * sector * (0.9 * curtains * rays + 0.22) * night;
}

vec3 aurora(vec3 ro, vec3 rd, float tca, float d2) {
    vec3 sum = vec3(0.0);
    // Quick test: the shells are so close together that a ray meets the polar caps on all of them or on none. Look at the two
    // ends of the stack (or the outer one and the tangent point for a grazing ray), with a margin for the limb where the hit
    // points move apart: only then the 16 shells are worth their cost.
    float rOut = AURORA_SHELL[AURORA_N - 1], rIn = AURORA_SHELL[0];
    if (d2 >= rOut * rOut) return sum;
    vec3 pOut = normalize(ro + rd * (tca - sqrt(rOut * rOut - d2)));
    bool maybe = abs(dot(pOut, MAG_POLE)) > 0.76;
    if (!maybe) {
        vec3 pOther = normalize(ro + rd * (d2 < rIn * rIn ? tca - sqrt(rIn * rIn - d2) : tca)); // the inner shell, or the tangent point
        maybe = abs(dot(pOther, MAG_POLE)) > 0.76;
    }
    if (!maybe) return sum;
    for (int i = 0; i < AURORA_N; i++) {
        float r = AURORA_SHELL[i];
        if (d2 >= r * r) continue;
        float t = tca - sqrt(r * r - d2);
        vec3 pn = normalize(ro + rd * t);
        if (abs(dot(pn, MAG_POLE)) < 0.88) continue;
        // a pixel's footprint on the shell in Earth radii (stretched where the ray grazes it), and in azimuth at the oval
        float px = uPixelAngle * t / clamp(abs(dot(rd, pn)), 0.25, 1.0);
        sum += AURORA_COLOUR[i] * auroraAt(pn, px * 3.1, px);
    }
    return sum * AURORA_GAIN;
}

vec3 gSun;     // the Sun's direction and the Moon's place as sunBody and moonBody use them: the real ones, or turned back
vec3 gMoonPos; // in time for one tap of the motion blur

// The Sun as a limb-darkened disc with a soft glow around it; the caller takes care of occlusion and reddening.
vec3 sunBody(vec3 rd) {
    if (dot(rd, gSun) < 0.9) return vec3(0.0);
    float ang = length(cross(rd, gSun));
    float mu = sqrt(max(1.0 - (ang / SUN_RADIUS) * (ang / SUN_RADIUS), 0.0));
    float disc = smoothstep(SUN_RADIUS + uPixelAngle, SUN_RADIUS - uPixelAngle, ang);
    float glow = 0.9 * exp(-ang / 0.012) + 0.06 * exp(-ang / 0.04);
    return vec3(1.0, 0.93, 0.80) * (60.0 * (0.55 + 0.45 * mu) * disc) + vec3(1.0, 0.85, 0.6) * (glow * SUN_E);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

// Smooth 3D value noise in [-1, 1]; on the sphere it has no seams and no poles
float valueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i);
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    return 2.0 * mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y), mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z) - 1.0;
}

// Fraction of a disc of angular radius rs (the Sun) covered by a disc of radius rm whose centre is d away
float lensFraction(float rs, float rm, float d) {
    if (d >= rs + rm) return 0.0;
    if (d <= abs(rm - rs)) return rm >= rs ? 1.0 : (rm * rm) / (rs * rs);
    float a = (d * d + rs * rs - rm * rm) / (2.0 * d * rs);
    float b = (d * d + rm * rm - rs * rs) / (2.0 * d * rm);
    float area = rs * rs * acos(clamp(a, -1.0, 1.0)) + rm * rm * acos(clamp(b, -1.0, 1.0))
               - 0.5 * sqrt(max((-d + rs + rm) * (d + rs - rm) * (d - rs + rm) * (d + rs + rm), 0.0));
    return area / (PI * rs * rs);
}

// How much of the Sun's disc the Moon covers, seen from the point p (Earth-centred, in Earth radii)
float sunCoveredByMoon(vec3 p) {
    vec3 v = uMoonPos - p;
    float dm = length(v);
    float rm = asin(min(R_MOON / dm, 1.0));
    float theta = length(cross(v / dm, uSunDir));
    return dot(v, uSunDir) > 0.0 ? lensFraction(SUN_ANGULAR_RADIUS, rm, theta) : 0.0;
}

// The Moon as a sphere at its true place and distance (so the parallax from the orbit is right), with its phase from the
// Sun's direction and the near side turned toward the Earth. Returns radiance and coverage.
vec4 moonBody(vec3 ro, vec3 rd) {
    vec3 oc = ro - gMoonPos;
    float b = dot(oc, rd);
    if (b > 0.0) return vec4(0.0);
    float t = -b;
    float d2m = max(dot(oc, oc) - b * b, 0.0);
    float px = uPixelAngle * t; // size of a pixel at the Moon
    float dm = sqrt(d2m);
    if (dm > R_MOON + 2.0 * px) return vec4(0.0);
    float cover = smoothstep(R_MOON + px, R_MOON - px, dm);
    vec3 surface = ro + rd * (t - sqrt(max(R_MOON * R_MOON - d2m, 0.0)));
    vec3 n = normalize(surface - gMoonPos);
    vec3 ex = normalize(-gMoonPos);                         // toward the Earth: longitude 0
    vec3 ez = normalize(vec3(0.0, 1.0, 0.0) - ex.y * ex);  // north
    vec3 ey = cross(ez, ex);                                // east
    vec2 uv = vec2(0.5 + atan(dot(n, ey), dot(n, ex)) / (2.0 * PI), 0.5 - asin(clamp(dot(n, ez), -1.0, 1.0)) / PI);
    float lod = log2(max(px * 2048.0 / (2.0 * PI * R_MOON), 1.0));
    vec3 albedo = textureLod(uMoon, uv, lod).rgb;
    float mu0 = max(dot(n, gSun), 0.0);
    float mu = max(dot(n, -rd), 0.02);
    float shade = 2.0 * mu0 / (mu0 + mu); // Lommel-Seeliger: a flat full moon
    // Earthshine: the Earth seen from the Moon has the opposite phase (full at new Moon) and lights the near side with its own
    // blue. Far brighter than the real 1 in 5000 of sunlight so that the dark part of a crescent shows (the "Da Vinci glow").
    vec3 earthshine = vec3(0.55, 0.72, 1.0) * (0.04 * (1.0 - uMoonPhase) * max(dot(n, ex), 0.0));
    vec3 lit = vec3(shade) + earthshine;
    if (uLunarEclipse > 0.5) { // the Earth in front of the Sun, seen from this spot of the Moon; its air lets a little red light through
        float dEarth = length(surface);
        float rEarth = asin(min(1.0 / dEarth, 1.0)) * 1.02;
        float shadowed = dot(-surface, gSun) > 0.0 ? lensFraction(SUN_ANGULAR_RADIUS, rEarth, length(cross(-surface / dEarth, gSun))) : 0.0;
        lit = shade * (1.0 - shadowed) + shadowed * 0.04 * vec3(1.0, 0.33, 0.12) + earthshine;
    }
    return vec4(albedo * (MOON_GAIN * SUN_E) * lit, cover);
}

// Length of the line of sight through the shell [r1, r2] in units of its thickness (1 looking straight down)
float shellPath(float d2, float r1, float r2) {
    if (d2 >= r2 * r2) return 0.0;
    float a = sqrt(r2 * r2 - d2);
    if (d2 >= r1 * r1) return 2.0 * a / (r2 - r1); // grazing: through the whole shell, both ways
    float b = sqrt(r1 * r1 - d2);
    return (d2 < R_EARTH * R_EARTH ? 1.0 : 2.0) * (a - b) / (r2 - r1);
}

// A layer with soft edges (real emission layers are bell-shaped, 8-10 km thick): shoulders of half the thickness on both
// sides at 40 % and 12 %. Only matters at the limb, where the line of sight grazes the layer; on the disc a box will do.
float softShell(float d2, float r1, float r2) {
    if (d2 < R_EARTH * R_EARTH) return shellPath(d2, r1, r2);
    float t = r2 - r1;
    return shellPath(d2, r1, r2)
         + 0.5 * (0.40 * (shellPath(d2, r1 - 0.5 * t, r1) + shellPath(d2, r2, r2 + 0.5 * t))
                + 0.12 * (shellPath(d2, r1 - t, r1 - 0.5 * t) + shellPath(d2, r2 + 0.5 * t, r2 + t)));
}

vec3 upperAtmosphere(vec3 ro, vec3 rd, float tca, float d2, float fw) { // fw: the size of a pixel in the closest-approach distance
    if (d2 >= UPPER_AIR_TOP * UPPER_AIR_TOP) return vec3(0.0);
    // the place the line of sight is "at": the tangent point at the limb, else where it enters the layers
    vec3 pn = normalize(ro + rd * (d2 >= R_EARTH * R_EARTH ? tca : tca - sqrt(max(UPPER_AIR_TOP * UPPER_AIR_TOP - d2, 0.0))));
    float sunDot = dot(pn, uSunDir);
    vec3 sum = vec3(0.0);

    float night = smoothstep(0.12, -0.25, sunDot);
    if (night > 0.0) {
        // Three taps across the pixel (along the closest-approach distance) average the thin bright line at the limb and the
        // step at the planet's edge: without them the glow shows pixel stairs. On the disc: straight through the layers (the
        // sum of the profile); at the limb the line of sight runs along the layers, shell by shell.
        vec3 glow = vec3(0.0);
        float dd = sqrt(d2);
        for (int t = 0; t < 3; t++) {
            float ds = dd + (float(t) - 1.0) * 0.4 * fw;
            float d2s = ds * ds;
            if (d2s < R_EARTH * R_EARTH) { glow += vec3(2.3896, 3.6279, 1.4336) * 0.3333; continue; }
            for (int k = 0; k < AIRGLOW_N; k++) {
                float r1 = AIRGLOW_R0 + AIRGLOW_STEP * float(k);
                glow += AIRGLOW_PROFILE[k] * (0.1333 * min(shellPath(d2s, r1, r1 + AIRGLOW_STEP), 24.0)); // 0.4 / 3; the cap tames the thin bright line a shell makes at its tangent
            }
        }
        // never even: gravity waves and patches of the layer make it brighter and dimmer along the limb, in broad patches and
        // finer streaks
        float patchy = perlin(vec2(pn.x * 6.0 + pn.z * 4.0, pn.y * 8.0 + 2.0 * pn.z)) + 0.6 * perlin(vec2(pn.x * 23.0 + pn.z * 17.0, pn.y * 31.0 + 5.0));
        float uneven = 0.4 + 0.6 * smoothstep(-0.5, 0.9, patchy);
        sum += glow * (AIRGLOW_GAIN * night * uneven);
    }

    float lat = abs(pn.y);
    float season = pn.y > 0.0 ? smoothstep(0.05, 0.30, uSunDir.y) : smoothstep(0.05, 0.30, -uSunDir.y);
    float band = smoothstep(0.74, 0.86, lat) * (1.0 - smoothstep(0.94, 0.985, lat));
    float lit = smoothstep(-0.17, -0.12, sunDot) * (1.0 - smoothstep(-0.02, 0.10, sunDot)); // sunlit up there, dark below
    float presence = season * band * lit;
    if (presence > 0.01) {
        float lon = atan(-pn.z, pn.x);
        float t = 6.2831853 * uAuroraPhase;
        // veils of wavy bands that run along the lines of latitude, broken up by large-scale patchiness
        float patches = smoothstep(-0.15, 0.55, perlin(vec2(lon * 9.0 + 2.0 * sin(t), pn.y * 7.0)));
        float waves = 0.5 + 0.5 * sin(pn.y * 220.0 + 5.0 * perlin(vec2(lon * 6.0 - sin(t + 1.0), pn.y * 11.0)));
        float veil = patches * (0.25 + 0.75 * waves * waves);
        sum += vec3(0.55, 0.75, 1.0) * (NLC_GAIN * presence * veil * min(shellPath(d2, NLC_LOW, NLC_HIGH), 5.0));
    }
    return sum;
}

// Flash light of the lightning at the cloud shell point with texture coordinates uv: x the bright core, y the wide soft halo
// in the cloud around it, 0 where there is none. Storms are active in patches that change every 20 seconds, and a flash is
// a few strokes in quick succession a little apart.
vec2 lightning(vec2 uv, float storm, float lat) {
    float tb = uTime / FLASH_PERIOD;
    float bucket = floor(tb);
    float inBucket = fract(tb) * FLASH_PERIOD;
    float era = floor(uTime / 20.0);
    vec2 base = floor(uv * FLASH_GRID);
    float chance = FLASH_CHANCE * uFlashRate * storm * exp(-(lat * lat) / (0.5 * 0.5)); // tropics: lat in radians
    float cosLat = max(cos(lat), 0.2);
    vec2 light = vec2(0.0);
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 cell = base + vec2(float(i), float(j));
            vec2 id = vec2(mod(cell.x, FLASH_GRID.x), cell.y);
            float h = hash12(id * 0.731 + vec2(bucket * 1.913, 7.31));
            if (h > chance * 2.5) continue; // the quick test first: most cells never flash
            float busy = hash12(floor(id / 4.0) * 0.37 + vec2(era * 2.17, 5.3)) > 0.6 ? 2.5 : 0.15; // about 1 in 3 patches is active
            if (h > chance * busy) continue;
            float start = hash12(id * 1.37 + vec2(bucket * 0.577, 3.1)) * (FLASH_PERIOD - 0.7);
            vec2 centre0 = (cell + vec2(hash12(id + 11.1), hash12(id + 23.7))) / FLASH_GRID;
            float size = 0.010 + 0.006 * hash12(id + 41.9);
            for (int k = 0; k < 3; k++) { // up to three strokes, each a little weaker and apart from the one before
                float since = inBucket - start - float(k) * (0.07 + 0.05 * hash12(id + float(k) * 5.1));
                if (since < 0.0) continue;
                float envelope = exp(-since / 0.09) * (1.0 - 0.3 * float(k));
                vec2 centre = centre0 + 0.35 * (vec2(hash12(id + float(k) * 7.7), hash12(id + float(k) * 3.3)) - 0.5) / FLASH_GRID;
                vec2 delta = vec2((uv.x - centre.x) * 2.0 * PI * cosLat, (uv.y - centre.y) * PI);
                float d2 = dot(delta, delta);
                light.x += envelope * exp(-0.5 * d2 / (size * size));
                light.y += envelope * exp(-0.5 * d2 / (2.25 * size * size)); // 1.5 x wider
            }
        }
    }
    return light;
}

// Zodiacal light (sunlight scattered by dust in the plane of the ecliptic: a faint cone that points away from the Sun
// along the ecliptic) and the gegenschein opposite the Sun
vec3 zodiacal(vec3 rd) {
    float c = dot(rd, uSunDir);
    if (c < 0.2 && c > -0.97) return vec3(0.0);
    float eps = acos(clamp(c, -1.0, 1.0));
    float sinBeta = dot(rd, uEclipticPole);
    float width = 0.10 + 0.18 * sin(min(eps, 1.5708));
    float cone = 0.045 * exp(-eps * 1.8) * exp(-(sinBeta * sinBeta) / (width * width));
    float gegen = 0.004 * exp(-pow((PI - eps) / 0.14, 2.0)) * exp(-(sinBeta * sinBeta) / 0.01);
    return vec3(1.0, 0.92, 0.78) * (cone + gegen);
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 oetfSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

void main() {
    vec3 ro = uCamPos;
    vec3 rd = normalize(uCamFwd + vNdc.x * uTanHalf.x * uCamRight + vNdc.y * uTanHalf.y * uCamUp);
    vec3 L = uSunDir;
    vec3 M = uMoonDir;
    vec3 V = -rd;

    // Closest approach of the ray to the planet's center; all hits derive from it and stay finite for misses.
    float tca = -dot(ro, rd);
    float d2 = max(dot(ro, ro) - tca * tca, 0.0);
    float dist = sqrt(d2);
    float fw = max(fwidth(dist), 1e-5);
    float covE = smoothstep(0.0, 1.0, (R_EARTH - dist) / fw + 0.5); // analytic edge anti-aliasing
    float covC = smoothstep(0.0, 1.0, (R_CLOUD - dist) / fw + 0.5);
    float covH = smoothstep(0.0, 1.0, (R_CIRRUS - dist) / fw + 0.5);

    float hE = sqrt(max(R_EARTH * R_EARTH - d2, 0.0));
    float hC = sqrt(max(R_CLOUD * R_CLOUD - d2, 0.0));
    float tE = tca - hE;
    vec3 N = normalize(ro + rd * tE);
    vec3 nC = normalize(ro + rd * (tca - hC)); // point on the cloud shell: this is where the parallax comes from
    vec3 nH = normalize(ro + rd * (tca - sqrt(max(R_CIRRUS * R_CIRRUS - d2, 0.0)))); // and on the cirrus shell

    // Texture coordinates and their gradients for every pixel (uniform control flow); fetches follow in branches
    float ndl = dot(N, L);
    float tShell = -ndl + sqrt(ndl * ndl + R_CLOUD * R_CLOUD - 1.0); // where the sun ray through the ground point crosses the cloud shell
    // The branch conditions depend on the distance from the centre, which hardly changes within a 2 x 2 pixel quad;
    // the margins keep every pixel that uses a result well inside the branch, so the derivatives stay valid.
    vec2 uvE = vec2(0.0);
    vec2 uvC = vec2(0.0);
    vec2 uvS = vec2(0.0);
    SeamGrad gE = SeamGrad(vec2(0.0), vec2(0.0));
    SeamGrad gC = gE;
    vec2 uvH = vec2(0.0);
    SeamGrad gS = gE;
    SeamGrad gH = gE;
    if (dist < R_CIRRUS + 4.0 * fw) {
        uvE = sphereUV(N);
        uvC = sphereUV(nC) - vec2(uCloudShift, 0.0);
        uvS = sphereUV(normalize(N + L * tShell)) - vec2(uCloudShift, 0.0); // the shadow moves with its cloud
        gE = seamGrad(uvE);
        gC = seamGrad(uvC);
        gS = seamGrad(uvS);
        uvH = sphereUV(nH) + vec2(0.31 - 1.7 * uCloudShift, 0.0); // its own pattern and a faster drift
        gH = seamGrad(uvH);
    }
    vec3 dirCel = vec3(0.0);
    vec2 uvSky = vec2(0.0);
    SeamGrad gSky = gE;
    if (dist > R_EARTH - 4.0 * fw) {
        dirCel = uSkyRot * rd;
        uvSky = equirectUV(dirCel);
        gSky = seamGrad(uvSky);
    }

    vec3 groundTotal = vec3(0.0);
    vec3 cloudTotal = vec3(0.0);
    float alphaC = 0.0;
    float alphaH = 0.0;
    vec3 cirrusTotal = vec3(0.0);

    // The air along the ray: the light scattered into it and its transmittance, read from the precomputed tables
    vec3 airLight = vec3(0.0);
    vec3 airT = vec3(1.0);
    if ((uSkip & (SKIP_ATMO_BACK | SKIP_ATMO_FRONT)) == 0) {
        vec3 scattered;
        atmosphereAlongRay(ro, rd, tca, d2, L, scattered, airT);
        airLight = SUN_E * ATMO_BOOST * scattered;
        if (uMoonPhase > 0.03) airLight += MOON_COLOR * uMoonPhase * ATMO_BOOST * 0.5 * scatteredFrom(ro, rd, tca, d2, M);
        airLight += AIRGLOW * 1.0 * (1.0 - airT); // the night glow of the air, in proportion to the air looked through
    }

    if (covH > 0.0) { // the planet or its cloud shells: the other 70 % of the screen is sky and skips all of this
        vec3 day = fetch(uDay, uvE, gE).rgb;
        vec4 topo = fetch(uTopo, uvE, gE);
        float landShare = 1.0 - topo.a; // the fetch is only needed over land
        float landBias = landShare > 0.0 ? landShare * clamp(textureLod(uClouds, uvC, 6.0).r - LAND_COLD_BASELINE, 0.0, 0.5) * LAND_COLD_BIAS : 0.0;

        // City lights and cloud edges go through thresholds, so single points pop in and out as they cross pixels at
        // 10 fps. Four samples per pixel (rotated grid), thresholded one by one and then averaged, keep the full sharpness.
        // Over the ocean the IR map is clean, so thin cloud may fade in early; over land cold ground looks like cloud
        float land = 1.0 - topo.a;
        vec2 cloudRamp = vec2(0.10, 0.75);
        float nightness = 1.0 - smoothstep(-0.12, 0.05, ndl);
        int taps = (uSkip & SKIP_CLOUD_TAPS) != 0 ? 1 : 4;
        float tapWeight = 1.0 / float(taps);
        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

        // City lights: only on the night side, and only where a coarse look finds any light within a few pixels
        float lightLuma = 0.0;
        if (nightness > 0.0 && (uSkip & SKIP_LIGHTS) == 0 && dot(textureGrad(uNight, uvE, gE.dx * 4.0, gE.dy * 4.0).rgb, LUMA) > LIGHTS_COARSE_MIN) {
            for (int i = 0; i < taps; i++) {
                vec2 o = SUBPIXEL[i];
                lightLuma += max(dot(textureGrad(uNight, uvE + gE.dx * o.x + gE.dy * o.y, gE.dx, gE.dy).rgb, LUMA) - 0.02, 0.0);
            }
            lightLuma *= tapWeight;
        }
        // The glow of the cities: light scattered in the haze and the clouds above them, a soft warm dome around every bright
        // place. The night map seen through two wide blurs (50 and 200 km at the ground, whatever the resolution).
        float cityGlow = 0.0;
        if (nightness > 0.0 && (uSkip & SKIP_LIGHTS) == 0) {
            float w1 = max(0.0044 / uPixelAngle, 2.0), w2 = max(0.017 / uPixelAngle, 6.0);
            cityGlow = 0.6 * dot(textureGrad(uNight, uvE, gE.dx * w1, gE.dy * w1).rgb, LUMA)
                     + 0.4 * dot(textureGrad(uNight, uvE, gE.dx * w2, gE.dy * w2).rgb, LUMA);
        }

        // Clouds and their shadow. A coarse look first (clear sky stays at zero). Then one fine sample: where it agrees with
        // the coarse one the texture is smooth under this pixel and one sample is all there is; only where the detail is
        // finer than a pixel (cloud edges, specks) the four samples per pixel are taken.
        float cloudCover = 0.0, alphaAvg = 0.0, shadowAvg = 0.0;
        if ((uSkip & SKIP_CLOUDS) == 0) {
            float coarse = textureGrad(uClouds, uvC, gC.dx * 3.0, gC.dy * 3.0).r;
            if (coarse > CLOUDS_COARSE_MIN) {
                float fine = textureGrad(uClouds, uvC, gC.dx, gC.dy).r;
                if (abs(fine - coarse) < DETAIL_MIN) {
                    cloudCover = clamp(fine - landBias, 0.0, 1.0);
                    alphaAvg = smoothstep(cloudRamp.x, cloudRamp.y, cloudCover);
                } else {
                    for (int i = 0; i < taps; i++) {
                        vec2 o = SUBPIXEL[i];
                        float cc = clamp(textureGrad(uClouds, uvC + gC.dx * o.x + gC.dy * o.y, gC.dx, gC.dy).r - landBias, 0.0, 1.0);
                        cloudCover += cc;
                        alphaAvg += smoothstep(cloudRamp.x, cloudRamp.y, cc);
                    }
                    cloudCover *= tapWeight; alphaAvg *= tapWeight;
                }
            }
            // the shadow only matters where the Sun is up
            if (ndl > -0.05) {
                float coarseS = textureGrad(uClouds, uvS, gS.dx * 3.0, gS.dy * 3.0).r;
                if (coarseS > CLOUDS_COARSE_MIN) {
                    float fineS = textureGrad(uClouds, uvS, gS.dx, gS.dy).r;
                    if (abs(fineS - coarseS) < DETAIL_MIN) {
                        shadowAvg = smoothstep(cloudRamp.x, cloudRamp.y, clamp(fineS - landBias, 0.0, 1.0));
                    } else {
                        for (int i = 0; i < taps; i++) {
                            vec2 o = SUBPIXEL[i];
                            float sc = clamp(textureGrad(uClouds, uvS + gS.dx * o.x + gS.dy * o.y, gS.dx, gS.dy).r - landBias, 0.0, 1.0);
                            shadowAvg += smoothstep(cloudRamp.x, cloudRamp.y, sc);
                        }
                        shadowAvg *= tapWeight;
                    }
                }
            }
        }

        // Relief normal from the topography map
        vec3 east = normalize(vec3(N.z, 0.0, -N.x) + vec3(1e-5, 0.0, 0.0));
        vec3 north = cross(N, east);
        vec3 tn = topo.rgb * 2.0 - 1.0;
        vec3 Nb = normalize(east * tn.x + north * tn.y + N * tn.z);

        // The IR-based map reads high-latitude cold ground as cloud, so only the brightest values count as dense cloud
        float cloudDetail = 0.0;
        if (alphaAvg > 0.0 && (uSkip & SKIP_CLOUDS) == 0) {
            cloudDetail = 0.5 * (perlin(nC.xy * 160.0) + perlin(nC.zy * 160.0 + 17.0)) + 0.25 * (perlin(nC.xy * 370.0 + 5.0) + perlin(nC.zy * 370.0 + 29.0));
            alphaAvg = clamp(alphaAvg + cloudDetail * CLOUD_DETAIL * 4.0 * alphaAvg * (1.0 - alphaAvg), 0.0, 1.0);
        }
        alphaC = 0.92 * alphaAvg;

        // ---- ground ----
        float horizon = smoothstep(-0.015, 0.06, ndl);
        float shadow = 1.0 - 0.75 * shadowAvg;
        vec3 sunE = SUN_E * sunTintAt(ATM_BOTTOM, ndl) * shadow * (1.0 - (uSolarEclipse > 0.5 && ndl > -0.1 ? sunCoveredByMoon(N) : 0.0));
        vec3 ground = day * sunE * (max(dot(Nb, L), 0.0) * horizon)
                    + sunE * (ggxSpec(N, L, V) * topo.a * horizon)
                    + day * SKY_AMBIENT * smoothstep(-0.1, 0.25, ndl);

        float ndlM = dot(N, M);
        float horizonM = smoothstep(-0.015, 0.06, ndlM);
        vec3 moonE = MOON_COLOR * uMoonPhase;
        float lumaDay = dot(day, vec3(0.2126, 0.7152, 0.0722));
        ground += moonE * (max(dot(Nb, M), 0.0) * horizonM) * (0.25 + 1.2 * lumaDay)
                + vec3(0.5, 0.6, 0.75) * uMoonPhase * (0.02 * ggxSpec(N, M, V) * topo.a * horizonM) // a faint silvery glint of the Moon on the sea
                + AIRGLOW;

        vec3 city = lightLuma * vec3(1.0, 0.70, 0.36) * 5.0 * nightness + cityGlow * vec3(1.0, 0.58, 0.26) * (CITY_GLOW_GAIN * nightness);
        ground += city;

        // ---- clouds: lit from the shell, so they stay lit past the ground terminator (twilight) ----
        float ndlC = dot(nC, L);
        float shellHorizon = 0.0706; // cos of the angle at which the earth shadows the 1.0025 shell
        float visC = smoothstep(0.0, 0.12, ndlC + shellHorizon);
        float diffC = max(ndlC, 0.0) + 0.10 * visC;
        float cloudAlbedo = mix(0.5, 0.9, smoothstep(0.35, 0.85, cloudCover)) * (1.0 + 0.07 * cloudDetail);
        // relief: compare the density here with the density a few pixels toward the Sun
        vec2 sunScreen = vec2(dot(L, uCamRight), dot(L, uCamUp));
        float sunSide = length(sunScreen);
        vec2 sunDir2 = sunScreen / max(sunSide, 1e-3);
        vec2 reliefOff = RELIEF_PX * (sunDir2.x * gC.dx + sunDir2.y * gC.dy);
        float relief = 0.0;
        if ((uSkip & (SKIP_RELIEF | SKIP_CLOUDS)) == 0 && alphaC > 0.01) {
            float hHere = textureGrad(uClouds, uvC, gC.dx * 2.0, gC.dy * 2.0).r;
            float hSun = textureGrad(uClouds, uvC + reliefOff, gC.dx * 2.0, gC.dy * 2.0).r;
            relief = clamp((hHere - hSun) * RELIEF_GAIN, -1.0, 1.0) * smoothstep(0.05, 0.4, sunSide) * alphaC;
        }
        vec3 cloudRad = cloudAlbedo * (SUN_E * sunTintAt(R_CLOUD, ndlC) * diffC * visC * (1.0 + RELIEF_STRENGTH * relief) * (1.0 - (uSolarEclipse > 0.5 && ndlC > -0.1 ? sunCoveredByMoon(nC * R_CLOUD) : 0.0))
                      + moonE * (2.0 * max(dot(nC, M), 0.0))
                      + AIRGLOW * 1.5
                      + city * 0.3);
        // lightning lights up the cloud deck from inside: only at night and only in big decks of the tropics
        if ((uSkip & SKIP_LIGHTNING) == 0 && nightness > 0.3 && alphaC > 0.05 && abs(nC.y) < 0.6) {
            float storm = smoothstep(0.35, 0.8, textureLod(uClouds, uvC, 5.0).r);
            if (storm > 0.0) {
                vec2 flash = lightning(uvC, storm, asin(nC.y));
                // soft clipping so that overlapping strokes do not burn a flat white patch; the light shows the thickness of the
                // cloud (bright where it is deep) and fades out with the cloud's edge
                vec3 flashColour = vec3(1.0, 0.92, 0.8) * flash.x + vec3(0.62, 0.72, 1.0) * (0.35 * flash.y);
                float depth = mix(0.3, 1.0, smoothstep(0.3, 1.0, cloudCover)) * smoothstep(0.05, 0.5, alphaC);
                cloudRad += (1.0 - exp(-0.8 * flashColour)) * (FLASH_GAIN * depth * nightness);
            }
        }

        // ---- cirrus: thin streaks high above the clouds, with a parallax of their own ----
        if ((uSkip & (SKIP_CLOUDS | SKIP_CIRRUS)) == 0 && dist < R_CIRRUS) {
            float high = textureGrad(uClouds, uvH, gH.dx * 3.0, gH.dy * 3.0).r;
            float thin = smoothstep(0.50, 0.92, high); // only the densest parts of the map: sparse cirrus, not a second cloud layer
            if (thin > 0.0) {
                // streaks along the lines of latitude: slow variation along them, fast across them
                float wisps = 0.65 * valueNoise3(vec3(nH.x * 14.0, nH.z * 14.0, nH.y * 60.0)) + 0.35 * valueNoise3(vec3(nH.x * 30.0, nH.z * 30.0, nH.y * 150.0) + 7.0);
                alphaH = 0.24 * thin * smoothstep(-0.2, 0.45, wisps);
                float ndlH = dot(nH, L);
                float visH = smoothstep(0.0, 0.12, ndlH + sqrt(1.0 - 1.0 / (R_CIRRUS * R_CIRRUS)));
                vec3 cirrusRad = 0.95 * (SUN_E * sunTintAt(R_CIRRUS, ndlH) * (max(ndlH, 0.0) + 0.10 * visH) * visH
                               + moonE * (1.5 * max(dot(nH, M), 0.0)) + AIRGLOW * 1.5);
                cirrusTotal = cirrusRad * airT + airLight;
            }
        }

        // ---- the air in front of the ground ----
        groundTotal = ground * airT + airLight;
        cloudTotal = cloudRad * airT + airLight;
    }

    // Space: stars and Milky Way, dimmed and reddened by the air they shine through at the limb
    vec3 sky = vec3(0.0);
    if (covE < 0.999) {
        vec3 milky = vec3(0.0);
        if ((uSkip & SKIP_MILKY) == 0) {
            milky = pow(fetch(uMilky, uvSky, gSky).rgb, vec3(2.2)) * MW_SCALE;
            milky = mix(vec3(dot(milky, vec3(0.2126, 0.7152, 0.0722))), milky, MW_SATURATION);
        }
        milky += zodiacal(rd);
        float limb = 1.0 - pow(airT.g, 1.5); // how much air the starlight crosses
        gSun = uSunDir;
        gMoonPos = uMoonPos;
#ifdef STAR_SPRITES
        vec3 stars = vec3(0.0); // drawn as sprites into the same buffer after this pass
#else
        vec3 stars = (uSkip & SKIP_STARS) == 0 ? starfield(dirCel, limb) * uStarGain : vec3(0.0);
#endif
        // faint stars close to the limb and behind the glow band are weaker, only well above it they shine fully
        vec3 backdrop = (milky + stars) * limbFade(dist);
        if ((uSkip & SKIP_SUN_MOON) != 0) {
            sky = backdrop * airT;
        } else if ((uSkip & SKIP_SUN_BLUR) != 0 || abs(uSkyBlur) < 1e-6) {
            vec4 moon = moonBody(ro, rd);
            sky = (mix(backdrop, moon.rgb, moon.a) + sunBody(rd) * (1.0 - moon.a)) * airT;
        } else {
            // The Sun and the Moon move across the picture much faster than the Earth (they are fixed in space while the
            // camera circles). At a low frame rate that shows as jumps, so they are smeared over the distance they travel in
            // one frame: the camera's turn is a rotation about the north axis, so earlier places of the Sun and the Moon are
            // the real ones turned about that axis.
            vec3 acc = vec3(0.0);
            for (int k = 0; k < BLUR_TAPS; k++) {
                float a = uSkyBlur * (0.5 - float(k) / float(BLUR_TAPS - 1));
                float c = cos(a), s = sin(a);
                gSun = vec3(c * uSunDir.x - s * uSunDir.z, uSunDir.y, s * uSunDir.x + c * uSunDir.z);
                gMoonPos = vec3(c * uMoonPos.x - s * uMoonPos.z, uMoonPos.y, s * uMoonPos.x + c * uMoonPos.z);
                vec4 moon = moonBody(ro, rd);
                acc += mix(backdrop, moon.rgb, moon.a) + sunBody(rd) * (1.0 - moon.a);
            }
            sky = acc / float(BLUR_TAPS) * airT;
        }
    }
    vec3 col = mix(airLight + sky, groundTotal, covE);
    col = mix(col, cloudTotal, alphaC * covC);
    col = mix(col, cirrusTotal, alphaH * covH);

    if ((uSkip & SKIP_AURORA) == 0) col += aurora(ro, rd, tca, d2);
    if ((uSkip & SKIP_UPPER_AIR) == 0) col += upperAtmosphere(ro, rd, tca, d2, fw); // the emission shells in front of the planet's night side and along the limb

#ifdef HDR_OUT
    outColor = vec4(col, 1.0); // linear light; resolve.frag tone maps, encodes and dithers
    return;
#endif
    // ---- output ----
    col = aces(max(col, 0.0));
    if (uP3 > 0.5) col = clamp(SRGB_TO_P3 * col, 0.0, 1.0);
    col = oetfSrgb(col);
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    outColor = vec4(col + (noise - 0.5) * uDither, 1.0);
}
