// Shared by earth.frag (stars per pixel, fallback) and star.vert/star.frag (stars as sprites): needs `uniform float uTime`.
const float PI = 3.14159265359;
const float R_EARTH = 1.0;
const float R_CLOUD = 1.0025;

const float STAR_FALLOFF = 0.16;
#ifndef STAR_SIGMA_BASE
#define STAR_SIGMA_BASE 1.2
#endif
#ifndef STAR_MAG_LIMIT
#define STAR_MAG_LIMIT 99.0 // per-pixel star path only: fainter stars are left out (the PC version sets it, see web/main.js)
#endif
const float STAR_PEAK_GAIN = 2.6 * (1.2 / STAR_SIGMA_BASE) * (1.2 / STAR_SIGMA_BASE); // a sharper star keeps its total light
const float STAR_SIGMA = STAR_SIGMA_BASE; // point spread width in pixels for faint stars; bright ones bloom
const float FLICKER_BASE = 0.05;  // everywhere, purely cosmetic: there is no scintillation above the atmosphere
const float FLICKER_LIMB = 0.45;  // extra for stars seen through the limb, where it is physical
const float FLICKER_RATE = 7.0;   // noise lattice cells per second

// Point spread width (sigma, pixels) and peak radiance of a star of magnitude mag
float starSigma(float mag) { return STAR_SIGMA * (1.0 + 0.25 * clamp(2.5 - mag, 0.0, 2.0)); } // bright stars bloom a little
float starPeak(float mag) { return STAR_PEAK_GAIN * pow(10.0, -STAR_FALLOFF * mag); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float perlin(vec2 p) { // 2D gradient noise, roughly in [-1, 1]
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash12(i) * 6.2831853;
    float b = hash12(i + vec2(1.0, 0.0)) * 6.2831853;
    float c = hash12(i + vec2(0.0, 1.0)) * 6.2831853;
    float d = hash12(i + vec2(1.0, 1.0)) * 6.2831853;
    float n00 = dot(vec2(cos(a), sin(a)), f);
    float n10 = dot(vec2(cos(b), sin(b)), f - vec2(1.0, 0.0));
    float n01 = dot(vec2(cos(c), sin(c)), f - vec2(0.0, 1.0));
    float n11 = dot(vec2(cos(d), sin(d)), f - vec2(1.0, 1.0));
    return mix(mix(n00, n10, u.x), mix(n01, n11, u.x), u.y) * 1.4142;
}

float twinkle(float seed) { // two octaves of high-frequency noise along time, one lane per star
    float lane = fract(seed * 0.6180339) * 64.0;
    float t = uTime * FLICKER_RATE;
    return 0.65 * perlin(vec2(lane, t)) + 0.35 * perlin(vec2(lane + 17.0, t * 2.7));
}
