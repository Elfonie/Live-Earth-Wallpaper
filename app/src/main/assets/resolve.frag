#version 300 es
precision highp float;
precision highp sampler2D;

// Last pass: the linear-light picture from the offscreen buffer -> ACES tone mapping, display encoding, dither.
out vec4 outColor;

uniform sampler2D uHdr;
uniform float uP3;     // 1.0: encode for Display P3, 0.0: sRGB
uniform float uDither; // 1 / (2^bits - 1)

const mat3 SRGB_TO_P3 = mat3(
    0.8224621, 0.0331941, 0.0170827,
    0.1775380, 0.9668058, 0.0723974,
    0.0000000, 0.0000000, 0.9105199);

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 oetfSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

void main() {
    vec3 col = aces(max(texelFetch(uHdr, ivec2(gl_FragCoord.xy), 0).rgb, 0.0));
    if (uP3 > 0.5) col = clamp(SRGB_TO_P3 * col, 0.0, 1.0);
    col = oetfSrgb(col);
    float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    outColor = vec4(col + (noise - 0.5) * uDither, 1.0);
}
