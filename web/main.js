// Earth Live for the PC (Wallpaper Engine web wallpaper / any browser with WebGL2): the same fragment shader as the Android app.
// Scene -> offscreen R11G11B10F buffer (earth.frag with HDR_OUT), then resolve.frag: ACES, sRGB, dither.
(function () {
  'use strict';
  // The same settings as the Android app (see project.json for the Wallpaper Engine properties that fill them).
  // fill: the Earth's diameter as a fraction of the screen height; starSigma and starLimit are compiled into the shader.
  const settings = {
    fps: 30, scale: 1.0, fill: 0.9, starSigma: 0.7, starLimit: 9.0,
    periodMinutes: 24, direction: 1, latitude: 21.4, starGain: 1, cloudSpeed: 100, cloudDirection: 1,
    off: {}, // effect name -> true while the effect is switched off
  };
  // SKIP_* bits of earth.frag for the effects that can be switched off
  const SKIP_BITS = { aurora: 16, lightning: 2048, airglow: 1024, cirrus: 4096, clouds: 256, citylights: 512, sharpclouds: 64, sunmoonblur: 8192 };
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
  const image = (url) => get(url, 'blob').then((b) => createImageBitmap(b, { premultiplyAlpha: 'none', colorSpaceConversion: 'none' }));

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
  let progScene, progResolve, hdrTex, hdrFbo, dayTex = null, dayMonth = 0;

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

  async function loadMonth(month) { // snow line, sea ice and vegetation follow the calendar: the Blue Marble month of today
    const bmp = await image('assets/day_' + String(month).padStart(2, '0') + '.jpg');
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
    exact(gl.TEXTURE_2D);
    hdrFbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, hdrFbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, hdrTex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  let width = 0, height = 0, tanX = 0, tanY = 0, dist = 0;
  function resize() {
    const dpr = params.has('w') ? 1 : window.devicePixelRatio || 1;
    const w = params.has('w') ? +params.get('w') : Math.floor(window.innerWidth * dpr * settings.scale);
    const h = params.has('h') ? +params.get('h') : Math.floor(window.innerHeight * dpr * settings.scale);
    if (w === width && h === height) return;
    width = canvas.width = Math.max(w, 2);
    height = canvas.height = Math.max(h, 2);
    if (params.has('w')) { canvas.style.width = width + 'px'; canvas.style.height = height + 'px'; }
    createTarget(width, height);
    dist = DISTANCE;
    tanY = Math.tan(Math.asin(1 / dist)) / settings.fill; // the silhouette spans `fill` of the height
    tanX = tanY * width / height;
  }

  // The cloud layer's drift against the ground in turns (a lap takes an hour at 100 %; negative: westward)
  function cloudShift(sec) {
    const rate = settings.cloudSpeed / 100 * settings.cloudDirection;
    if (rate === 0) return 0;
    const lap = 3600 / Math.abs(rate);
    return Math.sign(rate) * (sec % lap) / lap;
  }

  // How far the camera turned about the north axis during one frame: the Sun and the Moon are smeared over it, so they glide
  // instead of jumping at low frame rates (earth.frag, uSkyBlur)
  function cameraTurn(jd, d, period) {
    const frame = 1 / settings.fps;
    const a = Astro.orbitPose(jd, d, settings.latitude, period, settings.direction).position;
    const b = Astro.orbitPose(jd - frame / 86400, d, settings.latitude, period, settings.direction).position;
    let turn = Math.atan2(a[2], a[0]) - Math.atan2(b[2], b[0]);
    while (turn > Math.PI) turn -= 2 * Math.PI;
    while (turn < -Math.PI) turn += 2 * Math.PI;
    return turn;
  }

  function draw(nowMs) {
    const month = Astro.month(nowMs);
    if (month !== dayMonth && !draw.loading) { draw.loading = true; loadMonth(month).finally(() => { draw.loading = false; }); }
    const s = Astro.scene(nowMs);
    const period = settings.periodMinutes * 60;
    const pose = Astro.orbitPose(s.jd, dist, settings.latitude, period, settings.direction);
    const sec = nowMs / 1000;

    gl.bindFramebuffer(gl.FRAMEBUFFER, hdrFbo);
    gl.viewport(0, 0, width, height);
    gl.useProgram(progScene);
    const u3 = (n, v) => gl.uniform3f(loc(progScene, n), v[0], v[1], v[2]);
    u3('uCamPos', pose.position); u3('uCamRight', pose.right); u3('uCamUp', pose.up); u3('uCamFwd', pose.forward);
    gl.uniformMatrix3fv(loc(progScene, 'uSkyRot'), false, Astro.skyMatrix(s.jd, pose));
    gl.uniform1f(loc(progScene, 'uCloudShift'), cloudShift(sec));
    gl.uniform1i(loc(progScene, 'uSkip'), skipMask());
    gl.uniform1f(loc(progScene, 'uStarGain'), settings.starGain);
    gl.uniform1f(loc(progScene, 'uSkyBlur'), cameraTurn(s.jd, dist, period));
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
    gl.useProgram(progResolve);
    gl.uniform2f(loc(progResolve, 'uScreen'), width, height);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  async function start() {
    say('Loading Earth Live ...');
    resize();
    // Stars: a monitor pixel is several times bigger than the phone's, so the same width in pixels looks blurry and the
    // faint stars crowd the sky; sharper points and a magnitude limit bring the phone's look
    const defines = '#define HDR_OUT\n#define STAR_SIGMA_BASE ' + settings.starSigma.toFixed(2) + '\n#define STAR_MAG_LIMIT ' + settings.starLimit.toFixed(1) + '\n';
    const [vs, fs, rs, ...rest] = await Promise.all([shader('earth.vert'), shader('earth.frag'), shader('resolve.frag')]);
    const withDefines = (src) => src.replace(/#version 300 es\r?\n/, '#version 300 es\n' + defines);
    const pScene = program(vs, withDefines(fs), 'earth.frag');
    const pResolve = program(vs, rs, 'resolve.frag');

    const day = Astro.month(fixedMs || Date.now());
    const [night, topo, clouds, milky, moon] = await Promise.all(['night.jpg', 'topo.webp', 'clouds.jpg', 'milkyway.webp', 'moon.jpg'].map((n) => image('assets/' + n)));
    uploadImage(UNIT.night, night, gl.SRGB8_ALPHA8);
    uploadImage(UNIT.topo, topo, gl.RGBA8);
    uploadImage(UNIT.clouds, clouds, gl.R8);
    uploadImage(UNIT.milky, milky, gl.RGBA8);
    uploadImage(UNIT.moon, moon, gl.SRGB8_ALPHA8);
    [night, topo, clouds, milky, moon].forEach((b) => b.close());
    await Promise.all([loadMonth(day), loadAtmosphere(), loadStars()]);

    say('Compiling the shader (once, can take a little while) ...');
    progScene = await ready(pScene);
    progResolve = await ready(pResolve);

    gl.useProgram(progScene);
    const samplers = { uDay: 0, uNight: 1, uTopo: 2, uClouds: 3, uSkyCells: 4, uSkyStars: 5, uMilky: 6, uStarColors: 7, uMoon: 8, uTransmittance: 10, uScatR: 11, uScatM: 12, uScatMS: 13 };
    for (const n in samplers) gl.uniform1i(loc(progScene, n), samplers[n]);
    gl.uniform1f(loc(progScene, 'uDither'), 1 / 255);
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
  const handlers = {
    fps: (v) => { settings.fps = num(v); },
    renderscale: (v) => { settings.scale = num(v); resize(); },
    earthsize: (v) => { settings.fill = num(v) / 100; width = 0; resize(); }, // width = 0 forces the geometry to be redone
    orbitminutes: (v) => { settings.periodMinutes = num(v); },
    orbitdirection: (v) => { settings.direction = String(v) === '1' ? -1 : 1; },
    latitude: (v) => { settings.latitude = num(v); },
    starbrightness: (v) => { settings.starGain = num(v) / 100; },
    cloudspeed: (v) => { settings.cloudSpeed = num(v); },
    clouddirection: (v) => { settings.cloudDirection = String(v) === '1' ? -1 : 1; },
  };
  for (const k in SKIP_BITS) handlers[k] = (v) => { settings.off[k] = !(v === true || v === 'true' || v === 1); }; // switches: on by default
  window.wallpaperPropertyListener = {
    applyUserProperties(p) {
      for (const k in p) if (handlers[k] && p[k] && p[k].value !== undefined) handlers[k](p[k].value);
    },
  };
  // ?p.latitude=-40&p.aurora=false: the same properties from the address, for tests without Wallpaper Engine
  const fromUrl = {};
  params.forEach((v, k) => { if (k.startsWith('p.')) fromUrl[k.slice(2)] = { value: v === 'true' ? true : v === 'false' ? false : v }; });
  window.wallpaperPropertyListener.applyUserProperties(fromUrl);

  start().catch((e) => { console.error(e); say('Error: ' + e.message); });
})();
