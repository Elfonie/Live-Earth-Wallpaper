#version 300 es
precision highp float;

// The Gaussian of one star, added to the linear-light picture; the planet hides what is behind its disc.
in vec3 vColour;
in float vSigma;
in float vSize;
in float vCore;
out vec4 outColor;

uniform vec3 uCamPos;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform vec3 uCamFwd;
uniform vec2 uTanHalf;
uniform vec2 uViewport;    // pixels
uniform float uPixelAngle; // radians per pixel at the screen centre

const float R_EARTH = 1.0;

void main() {
    vec2 p = (gl_PointCoord - 0.5) * vSize; // pixels from the star's centre
    float r2 = dot(p, p);
    float reach = vCore + 3.0 * vSigma;
    if (r2 > reach * reach) discard;
    float edge = max(sqrt(r2) - vCore, 0.0);
    vec2 ndc = gl_FragCoord.xy / uViewport * 2.0 - 1.0;
    vec3 rd = normalize(uCamFwd + ndc.x * uTanHalf.x * uCamRight + ndc.y * uTanHalf.y * uCamUp);
    float tca = -dot(uCamPos, rd);
    float dist = sqrt(max(dot(uCamPos, uCamPos) - tca * tca, 0.0));
    float hidden = smoothstep(0.0, 1.0, (R_EARTH - dist) / (uPixelAngle * max(tca, 1.0)) + 0.5); // same edge as covE in earth.frag
    outColor = vec4(vColour * (exp(-0.5 * edge * edge / (vSigma * vSigma)) * (1.0 - hidden)), 1.0);
}
