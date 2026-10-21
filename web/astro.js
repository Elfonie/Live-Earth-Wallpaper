// Sun, Moon, orbit camera and sky frame: the same math as Astronomy.kt, OrbitCamera.kt and SkyFrame.kt (tools/preview.py has it
// in Python). Earth-fixed frame: x = (0 N, 0 E), y = north pole, z = (0 N, 90 W). Classic script (no modules: file:// pages).
(function (g) {
  const J2000 = 2451545.0;
  const rad = Math.PI / 180;
  const mod = (a, n) => ((a % n) + n) % n;
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const norm = (v) => { const n = Math.hypot(v[0], v[1], v[2]); return [v[0] / n, v[1] / n, v[2] / n]; };

  const julianDate = (ms) => ms / 86400000 + 2440587.5;
  function gmst(jd) {
    const t = (jd - J2000) / 36525;
    return mod(280.46061837 + 360.98564736629 * (jd - J2000) + 0.000387933 * t * t - t * t * t / 38710000, 360);
  }
  function sunEq(jd) {
    const n = jd - J2000;
    const gg = (357.528 + 0.9856003 * n) * rad;
    const lam = (280.46 + 0.9856474 * n) * rad + (1.915 * Math.sin(gg) + 0.02 * Math.sin(2 * gg)) * rad;
    const eps = (23.439 - 0.0000004 * n) * rad;
    return [mod(Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam)) / rad, 360), Math.asin(Math.sin(eps) * Math.sin(lam)) / rad];
  }
  // Meeus 47.A/47.B, largest terms: [coefficient (1e-6 deg), D, M, M', F]
  const LON = [[6288774, 0, 0, 1, 0], [1274027, 2, 0, -1, 0], [658314, 2, 0, 0, 0], [213618, 0, 0, 2, 0], [-185116, 0, 1, 0, 0], [-114332, 0, 0, 0, 2], [58793, 2, 0, -2, 0], [57066, 2, -1, -1, 0], [53322, 2, 0, 1, 0], [45758, 2, -1, 0, 0], [-40923, 0, 1, -1, 0], [-34720, 1, 0, 0, 0], [-30383, 0, 1, 1, 0], [15327, 2, 0, 0, -2], [-12528, 0, 0, 1, 2], [10980, 0, 0, 1, -2]];
  const LAT = [[5128122, 0, 0, 0, 1], [280602, 0, 0, 1, 1], [277693, 0, 0, 1, -1], [173237, 2, 0, 0, -1], [55413, 2, 0, -1, 1], [46271, 2, 0, -1, -1], [32573, 2, 0, 0, 1], [17198, 0, 0, 2, 1], [9266, 2, 0, 1, -1], [8822, 0, 0, 2, -1], [8216, 2, -1, 0, -1], [4324, 2, 0, -2, -1], [4200, 2, 0, 1, 1], [-3359, 2, 1, 0, -1]];
  const DIST = [[-20905355, 0, 0, 1, 0], [-3699111, 2, 0, -1, 0], [-2955968, 2, 0, 0, 0], [-569925, 0, 0, 2, 0], [48888, 0, 1, 0, 0], [-3149, 0, 0, 0, 2], [246158, 2, 0, -2, 0], [-152138, 2, -1, -1, 0], [-170733, 2, 0, 1, 0], [-204586, 2, -1, 0, 0], [-129620, 0, 1, -1, 0], [108743, 1, 0, 0, 0], [104755, 0, 1, 1, 0], [10321, 2, 0, 0, -2], [79661, 0, 0, 1, 2], [34782, 0, 0, 1, -2], [-23210, 4, 0, -1, 0], [-21636, 4, 0, -2, 0], [24208, 2, 1, -1, 0], [30824, 2, 1, 0, 0], [-8379, 1, 0, -1, 0], [-16675, 1, 1, 0, 0], [-12831, 2, -1, 1, 0], [-10445, 2, 0, 2, 0], [-11650, 4, 0, 0, 0], [14403, 2, 0, -3, 0], [-7003, 0, 1, -2, 0]];

  function moonArgs(jd) {
    const t = (jd - J2000) / 36525;
    return {
      t,
      lp: 218.3164477 + 481267.88123421 * t,
      d: (297.8501921 + 445267.1114034 * t) * rad,
      m: (357.5291092 + 35999.0502909 * t) * rad,
      mp: (134.9633964 + 477198.8675055 * t) * rad,
      f: (93.272095 + 483202.0175233 * t) * rad,
      e: 1 - 0.002516 * t - 0.0000074 * t * t,
    };
  }
  function moonEq(jd) {
    const a = moonArgs(jd);
    const sum = (tab) => tab.reduce((s, [c, d, m, mp, f]) => s + c * Math.pow(a.e, Math.abs(m)) * Math.sin(d * a.d + m * a.m + mp * a.mp + f * a.f), 0) * 1e-6;
    const lam = (a.lp + sum(LON)) * rad;
    const bet = sum(LAT) * rad;
    const eps = (23.439291 - 0.0130042 * a.t) * rad;
    return [
      mod(Math.atan2(Math.sin(lam) * Math.cos(eps) - Math.tan(bet) * Math.sin(eps), Math.cos(lam)) / rad, 360),
      Math.asin(Math.sin(bet) * Math.cos(eps) + Math.cos(bet) * Math.sin(eps) * Math.sin(lam)) / rad,
    ];
  }
  function moonDistanceRadii(jd) { // in Earth radii (6378.14 km)
    const a = moonArgs(jd);
    const km = 385000.56 + DIST.reduce((s, [c, d, m, mp, f]) => s + c * Math.pow(a.e, Math.abs(m)) * Math.cos(d * a.d + m * a.m + mp * a.mp + f * a.f), 0) * 1e-3;
    return km / 6378.14;
  }
  function earthFixed(ra, dec, gm) {
    const lon = (ra - gm) * rad;
    const d = dec * rad;
    return [Math.cos(d) * Math.cos(lon), Math.sin(d), -Math.cos(d) * Math.sin(lon)];
  }

  // Orbit camera (OrbitCamera.kt)
  const FOV_Y_DEG = 30, LATITUDE_DEG = 21.4, PERIOD_S = 24 * 60;
  function orbitPose(jd, dist) {
    const u = ((jd - J2000) * 86400 % PERIOD_S) / PERIOD_S * 2 * Math.PI;
    const lat = LATITUDE_DEG * rad;
    const inertial = [dist * Math.cos(lat) * Math.cos(u), -dist * Math.cos(lat) * Math.sin(u), dist * Math.sin(lat)];
    const gg = gmst(jd) * rad;
    const c = Math.cos(gg), s = Math.sin(gg);
    const position = [c * inertial[0] + s * inertial[1], inertial[2], s * inertial[0] - c * inertial[1]];
    const n = norm(position);
    const up = norm([-n[1] * n[0], 1 - n[1] * n[1], -n[1] * n[2]]);
    const forward = norm([-position[0], -position[1], -position[2]]);
    return { position, forward, right: cross(forward, up), up };
  }

  // Sky attached to the camera with a slow drift (SkyFrame.kt): column-major 3x3 for uniformMatrix3fv
  const TO_GAL = [[-0.0548755604, -0.8734370902, -0.4838350155], [0.4941094279, -0.44482963, 0.7469822445], [-0.867666149, -0.1980763734, 0.4559837762]];
  const fromGal = (v) => [0, 1, 2].map((j) => TO_GAL[0][j] * v[0] + TO_GAL[1][j] * v[1] + TO_GAL[2][j] * v[2]);
  const VIEW_L = 320 * rad, TILT = 35 * rad, DRIFT_S = 3 * 3600;
  const fwdC = fromGal([Math.cos(VIEW_L), Math.sin(VIEW_L), 0]);
  const poleC = fromGal([0, 0, 1]);
  const along = cross(poleC, fwdC);
  const upC = [0, 1, 2].map((i) => Math.cos(TILT) * poleC[i] + Math.sin(TILT) * along[i]);
  const base = [cross(fwdC, upC), upC, fwdC]; // columns
  function skyMatrix(jd, pose) {
    const alpha = ((jd - J2000) * 86400 % DRIFT_S) / DRIFT_S * 2 * Math.PI;
    const c = Math.cos(alpha), s = Math.sin(alpha);
    const rows = [pose.right, pose.up, pose.forward];
    const drifted = [[0, 1, 2].map((i) => c * rows[0][i] + s * rows[2][i]), rows[1], [0, 1, 2].map((i) => -s * rows[0][i] + c * rows[2][i])];
    const m = [0, 1, 2].map((row) => [0, 1, 2].map((col) => base[0][row] * drifted[0][col] + base[1][row] * drifted[1][col] + base[2][row] * drifted[2][col]));
    return new Float32Array([m[0][0], m[1][0], m[2][0], m[0][1], m[1][1], m[2][1], m[0][2], m[1][2], m[2][2]]);
  }

  const ECLIPSE_COS = Math.cos(2.2 * rad);
  g.Astro = {
    FOV_Y_DEG,
    julianDate,
    // Everything the shader needs for one instant
    scene(ms) {
      const jd = julianDate(ms);
      const gm = gmst(jd);
      const sun = earthFixed(...sunEq(jd), gm);
      const moon = earthFixed(...moonEq(jd), gm);
      const sm = dot(sun, moon);
      return {
        jd, sun, moon,
        moonPos: moon.map((x) => x * moonDistanceRadii(jd)),
        phase: Math.pow((1 - sm) * 0.5, 2.5),
        solarEclipse: sm > ECLIPSE_COS ? 1 : 0,
        lunarEclipse: -sm > ECLIPSE_COS ? 1 : 0,
        eclipticPole: earthFixed(270, 90 - 23.439291, gm),
      };
    },
    orbitPose,
    skyMatrix,
    distanceForFill(tanHalf, fill) { return 1 / Math.sin(Math.atan(fill * tanHalf)); },
    month(ms) { return new Date(ms).getUTCMonth() + 1; },
  };
})(window);
