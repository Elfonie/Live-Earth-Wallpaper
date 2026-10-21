#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

// One point sprite per catalog star that is on screen (the list is rebuilt now and then on the CPU, see StarSprites.kt).
// Position, size, colour, atmosphere dimming and flicker are worked out once per star here; star.frag draws the Gaussian.
layout(location = 0) in vec4 aDirMag;   // J2000 unit direction, magnitude
layout(location = 1) in vec2 aTintSeed; // colour index (0..255), flicker seed

uniform mat3 uSkyRot;      // Earth-fixed ray -> J2000 direction, as in earth.frag
uniform vec3 uCamPos;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform vec3 uCamFwd;
uniform vec2 uTanHalf;
uniform float uTime;
uniform sampler2D uStarColors;

#include "sky_common.glsl"
#include "atmosphere.glsl"

out vec3 vColour;
out float vSigma;
out float vSize;

void main() {
    vec3 rd = transpose(uSkyRot) * aDirMag.xyz; // the star's direction in the renderer frame
    float f = dot(rd, uCamFwd);
    float mag = aDirMag.w;
    float sigma = starSigma(mag);
    vSigma = sigma;
    vSize = 2.0 * ceil(3.0 * sigma) + 2.0;
    gl_PointSize = vSize;
    if (f <= 0.0) { // behind the camera: push it out of the clip volume
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vColour = vec3(0.0);
        return;
    }
    gl_Position = vec4(dot(rd, uCamRight) / (f * uTanHalf.x), dot(rd, uCamUp) / (f * uTanHalf.y), 0.0, 1.0);

    float rayTca = -dot(uCamPos, rd);
    vec3 air = skyTransmittance(uCamPos, rd, rayTca, max(dot(uCamPos, uCamPos) - rayTca * rayTca, 0.0));
    float limb = 1.0 - pow(air.g, 1.5);
    float flicker = 1.0 + (FLICKER_BASE + FLICKER_LIMB * limb) * twinkle(aTintSeed.y);
    vec3 tint = texelFetch(uStarColors, ivec2(int(aTintSeed.x), 0), 0).rgb;
    vColour = tint * starPeak(mag) * flicker * air;
}
