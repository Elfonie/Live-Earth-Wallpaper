// Lookup of the precomputed atmosphere (tables made by tools/make_atmosphere.py: same parameters, inverse mapping).
// Units are Earth radii. All functions are for a viewer outside the air: the ray is moved to the top of the atmosphere.
precision highp sampler3D;

uniform sampler2D uTransmittance; // sunlight reaching the top of the air from (height, zenith): 256 (zenith) x 64 (height)
uniform sampler3D uScatR;         // Rayleigh single scattering, 3D (nu * mu_s, mu, r) = 256 x 128 x 32
uniform sampler3D uScatM;         // Mie single scattering
uniform sampler3D uScatMS;        // multiple scattering (isotropic)

const float ATM_BOTTOM = 1.0;
const float ATM_TOP = 1.0156961;                 // 100 km
const float ATM_H = 0.1775;                      // sqrt(top^2 - bottom^2): distance from the top to the horizon
const float SUN_ANGULAR_RADIUS = 0.004675;
const float MU_S_MIN = -0.2079117;               // cos 102 degrees: the Sun's lowest zenith in the tables
const float MIE_G = 0.8;
const float TRANS_W = 256.0, TRANS_H = 64.0;
const float NU_SIZE = 8.0, MU_S_SIZE = 32.0, MU_SIZE = 128.0, R_SIZE = 32.0;

float unitToTex(float x, float size) { return 0.5 / size + x * (1.0 - 1.0 / size); }

float distanceToTop(float r, float mu) {
    return max(-r * mu + sqrt(max(r * r * (mu * mu - 1.0) + ATM_TOP * ATM_TOP, 0.0)), 0.0);
}

float distanceToBottom(float r, float mu) {
    return max(-r * mu - sqrt(max(r * r * (mu * mu - 1.0) + ATM_BOTTOM * ATM_BOTTOM, 0.0)), 0.0);
}

// Transmittance from height r along a ray with zenith cosine mu to the top of the air (the ray must not hit the ground)
vec3 transmittanceToTop(float r, float mu) {
    float rho = sqrt(max(r * r - ATM_BOTTOM * ATM_BOTTOM, 0.0));
    float d = distanceToTop(r, mu);
    float dMin = ATM_TOP - r;
    float dMax = rho + ATM_H;
    float xMu = (d - dMin) / (dMax - dMin);
    float xR = rho / ATM_H;
    return texture(uTransmittance, vec2(unitToTex(clamp(xMu, 0.0, 1.0), TRANS_W), unitToTex(clamp(xR, 0.0, 1.0), TRANS_H))).rgb;
}

// Transmittance over the distance d of a ray from height r; `ground`: the ray ends on the planet
vec3 transmittanceAlong(float r, float mu, float d, bool ground) {
    float rD = clamp(sqrt(d * d + 2.0 * r * mu * d + r * r), ATM_BOTTOM, ATM_TOP);
    float muD = clamp((r * mu + d) / rD, -1.0, 1.0);
    if (ground) return min(transmittanceToTop(rD, -muD) / max(transmittanceToTop(r, -mu), vec3(1e-7)), vec3(1.0));
    return min(transmittanceToTop(r, mu) / max(transmittanceToTop(rD, muD), vec3(1e-7)), vec3(1.0));
}

// Sunlight reaching height r at sun zenith cosine muS (0 behind the planet; the soft edge is the Sun's size)
vec3 transmittanceToSun(float r, float muS) {
    float sinH = ATM_BOTTOM / r;
    float cosH = -sqrt(max(1.0 - sinH * sinH, 0.0));
    return transmittanceToTop(r, muS) * smoothstep(-sinH * SUN_ANGULAR_RADIUS, sinH * SUN_ANGULAR_RADIUS, muS - cosH);
}

// Sunlight colour at height r (1 at the zenith on the ground): reddens and fades towards the horizon
vec3 sunTintAt(float r, float muS) {
    return transmittanceToSun(r, muS) / transmittanceToTop(ATM_BOTTOM, 1.0);
}

float phaseRayleigh(float c) { return 3.0 / (16.0 * PI) * (1.0 + c * c); }

float phaseMie(float c) { // Cornette-Shanks
    float g2 = MIE_G * MIE_G;
    return 3.0 / (8.0 * PI) * (1.0 - g2) / (2.0 + g2) * (1.0 + c * c) / pow(1.0 + g2 - 2.0 * MIE_G * c, 1.5);
}

vec4 scatteringCoords(float r, float mu, float muS, float nu, bool ground) {
    float rho = sqrt(max(r * r - ATM_BOTTOM * ATM_BOTTOM, 0.0));
    float uR = unitToTex(rho / ATM_H, R_SIZE);
    float rMu = r * mu;
    float discriminant = rMu * rMu - r * r + ATM_BOTTOM * ATM_BOTTOM;
    float uMu;
    if (ground) {
        float d = -rMu - sqrt(max(discriminant, 0.0));
        float dMin = r - ATM_BOTTOM;
        float dMax = rho;
        uMu = 0.5 - 0.5 * unitToTex(dMax == dMin ? 0.0 : clamp((d - dMin) / (dMax - dMin), 0.0, 1.0), MU_SIZE / 2.0);
    } else {
        float d = -rMu + sqrt(max(discriminant + ATM_H * ATM_H, 0.0));
        float dMin = ATM_TOP - r;
        float dMax = rho + ATM_H;
        uMu = 0.5 + 0.5 * unitToTex(clamp((d - dMin) / (dMax - dMin), 0.0, 1.0), MU_SIZE / 2.0);
    }
    float d = distanceToTop(ATM_BOTTOM, muS);
    float dMin = ATM_TOP - ATM_BOTTOM;
    float dMax = ATM_H;
    float a = (d - dMin) / (dMax - dMin);
    float bigD = distanceToTop(ATM_BOTTOM, MU_S_MIN);
    float bigA = (bigD - dMin) / (dMax - dMin);
    float uMuS = unitToTex(max(1.0 - a / bigA, 0.0) / (1.0 + a), MU_S_SIZE);
    return vec4((nu + 1.0) * 0.5, uMuS, uMu, uR);
}

// fast: one fetch from the nearest of the 8 slices in the scattering angle instead of two blended (half the fetches)
vec3 lookup3(sampler3D table, vec4 uvwz, bool fast) {
    float texX = uvwz.x * (NU_SIZE - 1.0);
    if (fast) return texture(table, vec3((floor(texX + 0.5) + uvwz.y) / NU_SIZE, uvwz.z, uvwz.w)).rgb;
    float x0 = floor(texX);
    float blend = texX - x0;
    vec3 a = texture(table, vec3((x0 + uvwz.y) / NU_SIZE, uvwz.z, uvwz.w)).rgb;
    vec3 b = texture(table, vec3((x0 + 1.0 + uvwz.y) / NU_SIZE, uvwz.z, uvwz.w)).rgb;
    return mix(a, b, blend);
}

// Radiance (per unit solar irradiance) scattered into the ray; the ray starts at the top of the air with zenith cosine mu
vec3 scatteredLight(float mu, float muS, float nu, bool ground, bool fast) {
    vec4 uvwz = scatteringCoords(ATM_TOP, mu, muS, nu, ground);
    return lookup3(uScatR, uvwz, fast) * phaseRayleigh(nu) + lookup3(uScatM, uvwz, fast) * phaseMie(nu) + lookup3(uScatMS, uvwz, fast);
}

// For the ray ro + rd*t (closest approach to the planet's centre: tca, squared distance d2): the light scattered into it
// from the direction `light` (per unit irradiance) and the transmittance of the air along it (to the ground when the ray
// hits the planet, else all the way through).
void atmosphereAlongRay(vec3 ro, vec3 rd, float tca, float d2, vec3 light, bool fast, out vec3 scattered, out vec3 transmittance) {
    scattered = vec3(0.0);
    transmittance = vec3(1.0);
    if (d2 >= ATM_TOP * ATM_TOP) return;
    bool ground = d2 < ATM_BOTTOM * ATM_BOTTOM;
    vec3 entry = ro + rd * (tca - sqrt(ATM_TOP * ATM_TOP - d2));
    vec3 up = entry / ATM_TOP;
    float mu = dot(up, rd);
    scattered = scatteredLight(mu, dot(up, light), dot(rd, light), ground, fast);
    transmittance = ground ? transmittanceAlong(ATM_TOP, mu, distanceToBottom(ATM_TOP, mu), true) : transmittanceToTop(ATM_TOP, mu);
}

// Only the light from `light` (second light, e.g. the Moon), same ray
vec3 scatteredFrom(vec3 ro, vec3 rd, float tca, float d2, vec3 light, bool fast) {
    if (d2 >= ATM_TOP * ATM_TOP) return vec3(0.0);
    bool ground = d2 < ATM_BOTTOM * ATM_BOTTOM;
    vec3 up = (ro + rd * (tca - sqrt(ATM_TOP * ATM_TOP - d2))) / ATM_TOP;
    return scatteredLight(dot(up, rd), dot(up, light), dot(rd, light), ground, fast);
}

// Transmittance of the air for a ray that does not hit the planet (stars, Sun and Moon seen past the limb)
vec3 skyTransmittance(vec3 ro, vec3 rd, float tca, float d2) {
    if (d2 >= ATM_TOP * ATM_TOP) return vec3(1.0);
    vec3 up = (ro + rd * (tca - sqrt(ATM_TOP * ATM_TOP - d2))) / ATM_TOP;
    return transmittanceToTop(ATM_TOP, dot(up, rd));
}
