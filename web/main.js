// Earth Live for the PC (Wallpaper Engine web wallpaper / any browser with WebGL2): the same fragment shader as the Android app.
// Scene -> offscreen R11G11B10F buffer (earth.frag with HDR_OUT), then resolve.frag: ACES, sRGB, dither.
(function () {
  'use strict';
  // The same settings as the Android app (see project.json for the Wallpaper Engine properties that fill them).
  // fill: the Earth's diameter as a fraction of the screen height; starSigma is the reference width at 1440 screen pixels.
  const settings = {
    fps: 30, scale: 1.0, fill: 0.9, starSigma: 0.7, starLimit: 10.0,
    periodMinutes: 24, direction: 1, latitude: 21.4, fixedView: false, longitude: 0,
    physicalSky: false, starGain: 1, starDensity: 1, starSize: 1, auroraGain: 1, airglowGain: 1,
    flashRate: 1, tier: 0, quality: 0, cloudSpeed: 100, cloudDirection: 1,
    off: {}, // effect name -> true while the effect is switched off
  };
  // SKIP_* bits of earth.frag for the effects that can be switched off
  const SKIP_BITS = { skyglow: 2, aurora: 16, lightning: 2048, airglow: 1024, cirrus: 4096, clouds: 256, citylights: 512, sharpclouds: 64, sunmoonblur: 8192, sun: 32, moon: 16384 };
  const skipMask = () => Object.keys(SKIP_BITS).reduce((m, k) => (settings.off[k] ? m | SKIP_BITS[k] : m), 0);
  // The camera is as far away as in the phone app (Earth fills 92 % of a 1280x2856 screen with a 30 degree vertical field),
  // so perspective, atmosphere, star density and sky detail match; only the field of view follows the screen's shape.
  const PHONE_TAN_X = Math.tan(15 * Math.PI / 180) * 1280 / 2856;
  const DISTANCE = 1 / Math.sin(Math.atan(0.92 * PHONE_TAN_X));
  const params = new URLSearchParams(location.search);
  if (params.has('fps')) settings.fps = +params.get('fps');
  if (params.has('scale')) settings.scale = +params.get('scale');
  if (params.has('sigma')) settings.starSigma = +params.get('sigma');
  if (params.has('maglimit')) settings.starLimit = +params.get('maglimit');
  const fixedMs = params.has('t') ? Date.parse(params.get('t')) : 0; // ?t=2026-10-20T02:05:00Z freezes the clock (screenshots)

  const canvas = document.getElementById('c');
  const status = document.getElementById('status');
  const gl = canvas.getContext('webgl2', { antialias: false, alpha: false, depth: false, stencil: false, powerPreference: 'high-performance' });
  let paused = false;
  canvas.addEventListener('webglcontextlost', () => location.reload()); // after a driver reset or a GPU switch: start over instead of staying black
  const say = (t) => { status.textContent = t; status.style.display = t ? 'block' : 'none'; };
  if (!gl || !gl.getExtension('EXT_color_buffer_float')) { say('WebGL2 with float render targets is required.'); return; }
  const parallel = gl.getExtension('KHR_parallel_shader_compile');
  const aniso = gl.getExtension('EXT_texture_filter_anisotropic');

  // ---- loading (XMLHttpRequest also works for file:// pages inside Wallpaper Engine) ----
  function get(url, type) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      x.open('GET', url);
      x.responseType = type;
      x.onload = () => (x.status === 200 || x.status === 0 ? resolve(x.response) : reject(new Error(url + ': ' + x.status)));
      x.onerror = () => reject(new Error('cannot load ' + url));
      x.send();
    });
  }
  // width: scale the picture down while decoding (2:1 maps), for the low quality tier
  const image = (url, width) => get(url, 'blob').then((b) => createImageBitmap(b, {
    premultiplyAlpha: 'none', colorSpaceConversion: 'none', ...(width ? { resizeWidth: width, resizeHeight: width / 2, resizeQuality: 'high' } : {}),
  }));

  const INCLUDE = /^\s*#include "([^"]+)"\s*$/gm;
  async function shader(name) {
    const text = await get('shaders/' + name, 'text');
    const parts = [];
    let last = 0, m;
    INCLUDE.lastIndex = 0;
    const found = [];
    while ((m = INCLUDE.exec(text))) found.push(m);
    for (const f of found) {
      parts.push(text.slice(last, f.index), await shader(f[1]));
      last = f.index + f[0].length;
    }
    parts.push(text.slice(last));
    return parts.join('');
  }

  // ---- GL helpers ----
  function compile(type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    return s;
  }
  function program(vsSrc, fsSrc, name) {
    const vs = compile(gl.VERTEX_SHADER, vsSrc), fs = compile(gl.FRAGMENT_SHADER, fsSrc);
    const p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    return { p, vs, fs, name };
  }
  async function ready(pr) { // wait for the (slow) compile without blocking the page, then check it
    if (parallel) while (!gl.getProgramParameter(pr.p, parallel.COMPLETION_STATUS_KHR)) await new Promise((r) => setTimeout(r, 50));
    if (!gl.getProgramParameter(pr.p, gl.LINK_STATUS)) {
      throw new Error(pr.name + ': ' + gl.getShaderInfoLog(pr.fs) + gl.getShaderInfoLog(pr.vs) + gl.getProgramInfoLog(pr.p));
    }
    return pr.p;
  }
  const locs = new Map();
  const loc = (p, n) => { let m = locs.get(p); if (!m) locs.set(p, (m = {})); return n in m ? m[n] : (m[n] = gl.getUniformLocation(p, n)); };

  function sampling(target, mip) {
    gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, mip ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
    gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(target, gl.TEXTURE_WRAP_S, gl.REPEAT); // longitude wraps
    gl.texParameteri(target, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    if (mip && aniso) gl.texParameterf(target, aniso.TEXTURE_MAX_ANISOTROPY_EXT, Math.min(8, gl.getParameter(aniso.MAX_TEXTURE_MAX_ANISOTROPY_EXT)));
  }
  // internal: RGBA8 | SRGB8_ALPHA8 | R8
  function uploadImage(unit, bitmap, internal, old) {
    gl.activeTexture(gl.TEXTURE0 + unit);
    const t = old || gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    const format = internal === gl.R8 ? gl.RED : gl.RGBA;
    gl.texImage2D(gl.TEXTURE_2D, 0, internal, format, gl.UNSIGNED_BYTE, bitmap);
    gl.generateMipmap(gl.TEXTURE_2D);
    sampling(gl.TEXTURE_2D, true);
    return t;
  }
  function exact(target) {
    gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(target, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(target, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  }
  function linearClamp(target) {
    gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(target, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(target, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    if (target === gl.TEXTURE_3D) gl.texParameteri(target, gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE);
  }

  const UNIT = { day: 0, night: 1, topo: 2, clouds: 3, cells: 4, stars: 5, milky: 6, colors: 7, moon: 8, hdr: 9, atmo: 10 };
  let progScene, progResolve, hdrTex, hdrFbo, dayTex = null, dayMonth = 0, nightTex = null, topoTex = null, cloudsTex = null, milkyTex = null, groundReady = false, reloading = false;
  // Map quality (tier): 0 standard (4096 x 2048 day, night and relief, 8192 clouds and Milky Way), 1 high (8192 day and relief, in
  // assets_hi; about 270 MB more graphics memory; night stays at 4096: its lights are smaller than a screen pixel at 8192 and look
  // weaker there), 2 low (2048 day, night and relief, 4096 clouds and Milky Way, the same files scaled down while decoding; about
  // half the graphics memory, for weak PCs and 1080p). At 1440p standard and high look the same; high shows more at 4K.
  const hiAvailable = gl.getParameter(gl.MAX_TEXTURE_SIZE) >= 8192;
  const wantTier = () => (settings.tier === 1 && !hiAvailable ? 0 : settings.tier);
  let groundTier = 0; // the quality the loaded maps have
  const groundUrl = (name, tier = groundTier) => (tier === 1 && name !== 'night.jpg' ? 'assets_hi/' : 'assets/') + name;
  const groundWidth = (tier = groundTier) => (tier === 2 ? 2048 : 0);
  const bigWidth = (tier = groundTier) => (tier === 2 ? 4096 : 0); // clouds and Milky Way
  function ensureGround() { if (groundReady && !reloading && wantTier() !== groundTier) reloadGround(); }

  async function loadAtmosphere() {
    const [tr, r, m, ms] = await Promise.all(['transmittance', 'scatter_r', 'scatter_m', 'scatter_ms'].map((n) => get('assets/atmosphere_' + n + '.bin', 'arraybuffer')));
    gl.activeTexture(gl.TEXTURE0 + UNIT.atmo);
    gl.bindTexture(gl.TEXTURE_2D, gl.createTexture());
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, 256, 64, 0, gl.RGBA, gl.HALF_FLOAT, new Uint16Array(tr));
    linearClamp(gl.TEXTURE_2D);
    [r, m, ms].forEach((buf, i) => {
      gl.activeTexture(gl.TEXTURE0 + UNIT.atmo + 1 + i);
      gl.bindTexture(gl.TEXTURE_3D, gl.createTexture());
      gl.texImage3D(gl.TEXTURE_3D, 0, gl.R11F_G11F_B10F, 256, 128, 32, 0, gl.RGB, gl.UNSIGNED_INT_10F_11F_11F_REV, new Uint32Array(buf));
      linearClamp(gl.TEXTURE_3D);
    });
  }

  async function loadStars() { // layout in tools/make_sky.py: 16 byte header, cells (RG32UI), stars (RG32UI, 2048 per row), 256 colours
    const buf = await get('assets/sky_stars.bin', 'arraybuffer');
    const head = new Uint32Array(buf, 0, 4);
    const count = head[1], cw = head[2], ch = head[3];
    const rows = Math.ceil(count / 2048);
    const cellBytes = cw * ch * 8, starBytes = rows * 2048 * 8;
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.activeTexture(gl.TEXTURE0 + UNIT.cells);
    gl.bindTexture(gl.TEXTURE_2D, gl.createTexture());
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RG32UI, cw, ch, 0, gl.RG_INTEGER, gl.UNSIGNED_INT, new Uint32Array(buf, 16, cw * ch * 2));
    exact(gl.TEXTURE_2D);
    gl.activeTexture(gl.TEXTURE0 + UNIT.stars);
    gl.bindTexture(gl.TEXTURE_2D, gl.createTexture());
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RG32UI, 2048, rows, 0, gl.RG_INTEGER, gl.UNSIGNED_INT, new Uint32Array(buf, 16 + cellBytes, rows * 2048 * 2));
    exact(gl.TEXTURE_2D);
    gl.activeTexture(gl.TEXTURE0 + UNIT.colors);
    gl.bindTexture(gl.TEXTURE_2D, gl.createTexture());
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 256, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(buf, 16 + cellBytes + starBytes, 1024));
    exact(gl.TEXTURE_2D);
  }

  async function reloadGround() { // the map quality changed: the same maps again at the other size
    if (reloading) return;
    reloading = true;
    try {
      groundTier = wantTier();
      const [night, topo, clouds, milky] = await Promise.all([
        image(groundUrl('night.jpg'), groundWidth()), image(groundUrl('topo.webp'), groundWidth()),
        image('assets/clouds.jpg', bigWidth()), image('assets/milkyway.webp', bigWidth())]);
      nightTex = uploadImage(UNIT.night, night, gl.SRGB8_ALPHA8, nightTex);
      topoTex = uploadImage(UNIT.topo, topo, gl.RGBA8, topoTex);
      cloudsTex = uploadImage(UNIT.clouds, clouds, gl.R8, cloudsTex);
      milkyTex = uploadImage(UNIT.milky, milky, gl.RGBA8, milkyTex);
      [night, topo, clouds, milky].forEach((b) => b.close());
      await loadMonth(dayMonth || Astro.month(fixedMs || Date.now()));
    } finally { reloading = false; }
    ensureGround(); // the property may have changed again meanwhile
  }

  async function loadMonth(month) { // snow line, sea ice and vegetation follow the calendar: the Blue Marble month of today
    const bmp = await image(groundUrl('day_' + String(month).padStart(2, '0') + '.jpg'), groundWidth());
    dayTex = uploadImage(UNIT.day, bmp, gl.SRGB8_ALPHA8, dayTex);
    bmp.close();
    dayMonth = month;
  }

  function createTarget(w, h) {
    if (hdrTex) { gl.deleteTexture(hdrTex); gl.deleteFramebuffer(hdrFbo); }
    hdrTex = gl.createTexture();
    gl.activeTexture(gl.TEXTURE0 + UNIT.hdr);
    gl.bindTexture(gl.TEXTURE_2D, hdrTex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R11F_G11F_B10F, w, h, 0, gl.RGB, gl.UNSIGNED_INT_10F_11F_11F_REV, null);
    linearClamp(gl.TEXTURE_2D); // the resolve pass averages (render scale above 1) or smooths (below 1) the buffer
    hdrFbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, hdrFbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, hdrTex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  // The canvas has the screen's size; the scene is drawn into a buffer of width x height (screen x render scale, up to 2 =
  // supersampling) and the resolve pass brings it to the canvas.
  let width = 0, height = 0, screenW = 0, screenH = 0, tanX = 0, tanY = 0, dist = 0;
  function resize() {
    const dpr = params.has('w') ? 1 : window.devicePixelRatio || 1;
    const sw = Math.max(params.has('w') ? +params.get('w') : Math.floor(window.innerWidth * dpr), 2);
    const sh = Math.max(params.has('h') ? +params.get('h') : Math.floor(window.innerHeight * dpr), 2);
    const w = Math.max(Math.round(sw * settings.scale), 2), h = Math.max(Math.round(sh * settings.scale), 2);
    if (sw === screenW && sh === screenH && w === width && h === height) return;
    screenW = canvas.width = sw;
    screenH = canvas.height = sh;
    width = w;
    height = h;
    if (params.has('w')) { canvas.style.width = sw + 'px'; canvas.style.height = sh + 'px'; }
    createTarget(width, height);
    dist = DISTANCE;
    // the silhouette spans `fill` of the height, or of the width on a screen that is taller than wide
    tanY = Math.tan(Math.asin(1 / dist)) / (settings.fill * Math.min(1, width / height));
    tanX = tanY * width / height;
  }

  // The camera's angle on its orbit and the clouds' drift follow the clock. A new orbit time, direction, cloud speed or direction
  // would throw them to some other place, so a change keeps them where they are (keepPlace) and goes on from there; the latitude is
  // flown to over a fraction of a second (latNow) instead of jumped to.
  let lastMs = null, orbitPhase = 0, cloudOffset = 0, latNow = 0;
  const periodS = () => settings.periodMinutes * 60;
  const cloudTurns = (sec) => settings.cloudSpeed / 100 * settings.cloudDirection * sec / 3600;
  function keepPlace(change) {
    if (lastMs === null) { change(); return; }
    const jd = Astro.julianDate(lastMs), sec = lastMs / 1000;
    const angle = Astro.orbitAngle(jd, periodS(), settings.direction, orbitPhase), clouds = cloudTurns(sec) + cloudOffset;
    change();
    orbitPhase = angle - Astro.orbitAngle(jd, periodS(), settings.direction);
    cloudOffset = clouds - cloudTurns(sec);
  }
  // The cloud layer's drift against the ground in turns (a lap takes an hour at 100 %; negative: westward)
  const cloudShift = (sec) => (((cloudTurns(sec) + cloudOffset) % 1) + 1) % 1;

  const cameraPose = (jd, latitude = latNow) => settings.fixedView
    ? Astro.fixedPose(dist, latitude, settings.longitude)
    : Astro.orbitPose(jd, dist, latitude, periodS(), settings.direction, orbitPhase);

  function setFixedView(enabled) {
    if (settings.fixedView && !enabled && lastMs !== null) {
      // Resume the orbit from the selected region, rather than jumping to the clock's original orbit phase.
      const base = Astro.orbitPose(Astro.julianDate(lastMs), dist, latNow, periodS(), settings.direction).position;
      orbitPhase = -settings.longitude * Math.PI / 180 - Math.atan2(base[2], base[0]);
    }
    settings.fixedView = enabled;
  }

  function starShape() {
    // Set apparent size in screen pixels first, then convert to buffer pixels. Only the sampling floor depends on render scale.
    const base = Math.max(settings.starSigma * screenH / 1440, 0.6) * height / screenH;
    const sigma = Math.max(base * Math.min(settings.starSize, 1), 0.35);
    // Above 100%, enlarge the bright core while keeping the edge softness and peak brightness fixed.
    // Below 100%, sharpen the point and retain its light rather than making faint stars disappear.
    const core = 1.2 * base * Math.max(settings.starSize - 1, 0);
    return { sigma, core, peakScale: Math.pow(height / 1440 / sigma, 2) };
  }

  // How far the camera turned about the north axis during one frame: the Sun and the Moon are smeared over it, so they glide
  // instead of jumping at low frame rates (earth.frag, uSkyBlur)
  function cameraTurn(jd, d, period) {
    if (settings.fixedView) return 0;
    const frame = 1 / settings.fps;
    const a = Astro.orbitPose(jd, d, latNow, period, settings.direction, orbitPhase).position;
    const b = Astro.orbitPose(jd - frame / 86400, d, latNow, period, settings.direction, orbitPhase).position;
    let turn = Math.atan2(a[2], a[0]) - Math.atan2(b[2], b[0]);
    while (turn > Math.PI) turn -= 2 * Math.PI;
    while (turn < -Math.PI) turn += 2 * Math.PI;
    return turn;
  }

  function draw(nowMs) {
    const month = Astro.month(nowMs);
    if (month !== dayMonth && !draw.loading) { draw.loading = true; loadMonth(month).finally(() => { draw.loading = false; }); }
    const s = Astro.scene(nowMs);
    const dtSec = lastMs === null ? 0 : Math.min(Math.abs(nowMs - lastMs) / 1000, 1);
    latNow = lastMs === null || fixedMs ? settings.latitude : latNow + (settings.latitude - latNow) * (1 - Math.exp(-dtSec / 0.12));
    lastMs = nowMs;
    const pose = cameraPose(s.jd);
    const sec = nowMs / 1000;

    gl.bindFramebuffer(gl.FRAMEBUFFER, hdrFbo);
    gl.viewport(0, 0, width, height);
    gl.useProgram(progScene);
    const u3 = (n, v) => gl.uniform3f(loc(progScene, n), v[0], v[1], v[2]);
    u3('uCamPos', pose.position); u3('uCamRight', pose.right); u3('uCamUp', pose.up); u3('uCamFwd', pose.forward);
    // the stars are placed as seen from the default latitude, whatever the camera's: a new latitude moves them on the screen
    // with the Moon and the Sun, as the real sky would move, instead of leaving them where they were
    // physicalSky: the stars stand in space like the Moon and move with the camera's orbit; else they are fixed to the picture and
    // drift slowly (placed as seen from the default latitude, so that a new latitude still moves them with the Moon)
    gl.uniformMatrix3fv(loc(progScene, 'uSkyRot'), false, settings.physicalSky ? Astro.skyMatrixInertial(s.jd)
      : Astro.skyMatrix(s.jd, cameraPose(s.jd, Astro.LATITUDE_DEG)));
    gl.uniform1f(loc(progScene, 'uCloudShift'), cloudShift(sec));
    gl.uniform1i(loc(progScene, 'uSkip'), skipMask());
    gl.uniform1f(loc(progScene, 'uStarGain'), settings.starGain);
    const shape = starShape();
    gl.uniform1f(loc(progScene, 'uStarSize'), shape.sigma);
    gl.uniform1f(loc(progScene, 'uStarCore'), shape.core);
    gl.uniform1f(loc(progScene, 'uStarPeakScale'), shape.peakScale);
    gl.uniform1f(loc(progScene, 'uStarDensity'), settings.starDensity);
    gl.uniform1f(loc(progScene, 'uAuroraGain'), settings.auroraGain);
    gl.uniform1f(loc(progScene, 'uAirglowGain'), settings.airglowGain);
    gl.uniform1f(loc(progScene, 'uFlashRate'), settings.flashRate);
    gl.uniform1i(loc(progScene, 'uQuality'), settings.quality);
    gl.uniform1f(loc(progScene, 'uSkyBlur'), cameraTurn(s.jd, dist, periodS()));
    gl.uniform1f(loc(progScene, 'uAuroraPhase'), (sec % 120) / 120);
    gl.uniform1f(loc(progScene, 'uTime'), sec % 3600);
    gl.uniform1f(loc(progScene, 'uPixelAngle'), 2 * tanX / width);
    gl.uniform2f(loc(progScene, 'uTanHalf'), tanX, tanY);
    u3('uSunDir', s.sun); u3('uMoonDir', s.moon); u3('uMoonPos', s.moonPos); u3('uEclipticPole', s.eclipticPole);
    gl.uniform1f(loc(progScene, 'uSolarEclipse'), s.solarEclipse);
    gl.uniform1f(loc(progScene, 'uLunarEclipse'), s.lunarEclipse);
    gl.uniform1f(loc(progScene, 'uMoonPhase'), s.phase);
    gl.uniform1f(loc(progScene, 'uP3'), 0);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, screenW, screenH);
    gl.useProgram(progResolve);
    gl.uniform2f(loc(progResolve, 'uScreen'), screenW, screenH);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  let sources;
  // Width, brightness and density are uniforms: slider changes and render scale need no shader recompile.
  function sceneProgram() {
    const defines = '#define HDR_OUT\n#define WEB_SETTINGS\n#define STAR_SIGMA_BASE 1.0\n#define STAR_FLUX_SIGMA 1.0' +
      '\n#define STAR_MAG_LIMIT ' + settings.starLimit.toFixed(1) + '\n';
    return program(sources.vs, sources.fs.replace(/#version 300 es\r?\n/, '#version 300 es\n' + defines), 'earth.frag');
  }
  function sceneUniforms() {
    gl.useProgram(progScene);
    const samplers = { uDay: 0, uNight: 1, uTopo: 2, uClouds: 3, uSkyCells: 4, uSkyStars: 5, uMilky: 6, uStarColors: 7, uMoon: 8, uTransmittance: 10, uScatR: 11, uScatM: 12, uScatMS: 13 };
    for (const n in samplers) gl.uniform1i(loc(progScene, n), samplers[n]);
    gl.uniform1f(loc(progScene, 'uDither'), 1 / 255);
  }

  async function start() {
    say('Loading Earth Live ...');
    resize();
    const [vs, fs, rs] = await Promise.all([shader('earth.vert'), shader('earth.frag'), shader('resolve.frag')]);
    sources = { vs, fs };
    const pScene = sceneProgram();
    const pResolve = program(vs, rs, 'resolve.frag');

    const day = Astro.month(fixedMs || Date.now());
    groundTier = wantTier();
    const [night, topo, clouds, milky, moon] = await Promise.all([
      image(groundUrl('night.jpg'), groundWidth()), image(groundUrl('topo.webp'), groundWidth()),
      image('assets/clouds.jpg', bigWidth()), image('assets/milkyway.webp', bigWidth()), image('assets/moon.jpg')]);
    nightTex = uploadImage(UNIT.night, night, gl.SRGB8_ALPHA8);
    topoTex = uploadImage(UNIT.topo, topo, gl.RGBA8);
    cloudsTex = uploadImage(UNIT.clouds, clouds, gl.R8);
    milkyTex = uploadImage(UNIT.milky, milky, gl.RGBA8);
    uploadImage(UNIT.moon, moon, gl.SRGB8_ALPHA8);
    [night, topo, clouds, milky, moon].forEach((b) => b.close());
    await Promise.all([loadMonth(day), loadAtmosphere(), loadStars()]);
    groundReady = true;

    say('Compiling the shader (once, can take a little while) ...');
    progScene = await ready(pScene);
    progResolve = await ready(pResolve);
    sceneUniforms();

    gl.useProgram(progResolve);
    gl.uniform1i(loc(progResolve, 'uHdr'), UNIT.hdr);
    gl.uniform1f(loc(progResolve, 'uP3'), 0);
    gl.uniform1f(loc(progResolve, 'uDither'), 1 / 255);
    gl.disable(gl.DEPTH_TEST);
    gl.bindVertexArray(gl.createVertexArray()); // attribute-less fullscreen triangle from gl_VertexID
    say('');

    let last = -1e9;
    function frame(t) {
      requestAnimationFrame(frame);
      if (paused || document.hidden) return; // Wallpaper Engine pauses (setPaused) when another window covers the wallpaper
      if (t - last < 1000 / settings.fps - 2) return; // frame limiter
      last = t;
      resize();
      draw(fixedMs || Date.now());
    }
    if (params.has('bench')) { // ?bench=1: median time of a frame with the GPU waited for (a hidden page gets no animation frames)
      resize();
      const px = new Uint8Array(4), times = [];
      for (let i = 0; i < 60; i++) {
        const t0 = performance.now();
        draw((fixedMs || Date.now()) + i * 100);
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        if (i >= 10) times.push(performance.now() - t0);
      }
      times.sort((a, b) => a - b);
      document.title = 'bench ' + width + 'x' + height + ' median ' + times[times.length >> 1].toFixed(1) + ' ms, min ' + times[0].toFixed(1) + ', max ' + times[times.length - 1].toFixed(1);
      return;
    }
    if (params.has('test')) { // ?test=1: draws on demand and returns a checksum of the picture, to check that every property changes it
      window.__test = {
        size: () => [width, height, screenW, screenH],
        snap() {
          draw(fixedMs || Date.now());
          const w = screenW, h = screenH, px = new Uint8Array(w * h * 4);
          gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, px);
          let hash = 2166136261 >>> 0, sum = 0;
          for (let i = 0; i < px.length; i += 4) { sum += px[i] + px[i + 1] + px[i + 2]; hash = Math.imul(hash ^ (px[i] + 7 * px[i + 1] + 31 * px[i + 2]), 16777619) >>> 0; }
          return { hash, mean: +(sum / (w * h * 3)).toFixed(3) };
        },
      };
      return;
    }
    if (params.has('capture')) { // a hidden page gets no animation frames: draw directly, then post the picture to tools/web_server.py
      resize();
      draw(fixedMs || Date.now());
      canvas.toBlob((b) => fetch('/capture.png', { method: 'POST', body: b }).then(() => { document.title = 'captured'; }));
      return;
    }
    requestAnimationFrame(frame);
    window.addEventListener('resize', resize);
  }

  // Wallpaper Engine user properties (project.json). WE sends all of them at load and then the changed ones.
  const num = (v) => (typeof v === 'number' ? v : parseFloat(v));
  const bounded = (v, lo, hi, fallback) => { const n = num(v); return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : fallback; };
  const enabled = (v) => v === true || v === 'true' || v === 1;
  const handlers = {
    fps: (v) => { settings.fps = num(v); },
    renderscale: (v) => { settings.scale = bounded(v, 0.5, 2, 1); resize(); },
    effectquality: (v) => { settings.quality = { '1': 1, '2': 2 }[String(v)] ?? 0; },
    texturequality: (v) => { settings.tier = { '1': 1, '2': 2 }[String(v)] ?? 0; ensureGround(); },
    earthsize: (v) => { settings.fill = num(v) / 100; width = 0; resize(); }, // width = 0 forces the geometry to be redone
    orbitminutes: (v) => keepPlace(() => { settings.periodMinutes = num(v); }),
    orbitdirection: (v) => keepPlace(() => { settings.direction = String(v) === '1' ? -1 : 1; }),
    latitude: (v) => { settings.latitude = num(v); },
    fixedview: (v) => setFixedView(enabled(v)),
    longitude: (v) => { settings.longitude = bounded(v, -180, 180, 0); },
    skymotion: (v) => { settings.physicalSky = String(v) === '1'; },
    starbrightness: (v) => { settings.starGain = num(v) / 100; },
    stardensity: (v) => { settings.starDensity = bounded(v, 0, 100, 100) / 100; },
    starsize: (v) => { settings.starSize = bounded(v, 50, 200, 100) / 100; },
    aurorastrength: (v) => { settings.auroraGain = bounded(v, 0, 200, 100) / 100; },
    airglowstrength: (v) => { settings.airglowGain = bounded(v, 0, 200, 100) / 100; },
    lightningrate: (v) => { settings.flashRate = num(v) / 100; },
    cloudspeed: (v) => keepPlace(() => { settings.cloudSpeed = num(v); }),
    clouddirection: (v) => keepPlace(() => { settings.cloudDirection = String(v) === '1' ? -1 : 1; }),
  };
  for (const k in SKIP_BITS) handlers[k] = (v) => { settings.off[k] = !(v === true || v === 'true' || v === 1); }; // switches: on by default
  window.wallpaperPropertyListener = {
    applyUserProperties(p) {
      for (const k in p) if (handlers[k] && p[k] && p[k].value !== undefined) handlers[k](p[k].value);
    },
    setPaused(isPaused) { paused = !!isPaused; },
  };
  // ?p.latitude=-40&p.aurora=false: the same properties from the address, for tests without Wallpaper Engine
  const fromUrl = {};
  params.forEach((v, k) => { if (k.startsWith('p.')) fromUrl[k.slice(2)] = { value: v === 'true' ? true : v === 'false' ? false : v }; });
  window.wallpaperPropertyListener.applyUserProperties(fromUrl);

  start().catch((e) => { console.error(e); say('Error: ' + e.message); });
})();
