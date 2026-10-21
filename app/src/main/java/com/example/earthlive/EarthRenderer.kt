package com.example.earthlive

import android.content.Context
import android.opengl.GLES30

/**
 * Draws one fullscreen triangle; the fragment shader ray-casts the globe (assets/earth.frag).
 * The camera flies a circular orbit at constant distance (see [OrbitCamera]); the Earth's rotation, the Sun and the
 * Moon follow the real UTC time. The stars are a calm backdrop that is not tied to the orbit (see [SkyFrame]).
 * Must be used on the thread that owns the EGL context.
 */
class EarthRenderer(
    private val context: Context,
    private val outputP3: Boolean,
    private val outputBits: Int,
) {
    private companion object {
        const val EARTH_RADIUS_KM = 6378.14
        const val AURORA_PERIOD_SECONDS = 120.0 // the aurora animation repeats seamlessly with this period
        const val HDR_UNIT = 9
        const val ATMOSPHERE_UNIT = 10 // transmittance, then the three scattering volumes
        val INCLUDE = Regex("#include \"([^\"]+)\"")
        val VERSION_LINE = Regex("#version 300 es\r?\n")
    }

    /** Profiling only (debug builds): bits that switch parts of the picture off, see SKIP_* in earth.frag. */
    var skipMask = 0
    private var featureMask = 0 // the same bits, from the user's switches
    private var fill = OrbitCamera.FILL_X
    private var latitudeDeg = OrbitCamera.LATITUDE_DEG
    private var periodSeconds = OrbitCamera.PERIOD_SECONDS
    private var renderScale = 1.0 // the picture is drawn at this fraction of the screen's size and stretched
    private var starGain = 1f
    private var flashRate = 1f
    private var cloudRate = 1.0
    private var orbitDirection = 1
    private var frameSeconds = 0.1 // the exposure of the Sun and Moon motion blur: one frame
    private var renderW = 1
    private var renderH = 1
    private var program = 0
    private var resolveProgram = 0
    private var activeProgram = 0
    private var useHdr = false       // the scene goes into an offscreen half-float buffer, resolve.frag puts it on screen
    private var hdrFramebuffer = 0
    private var hdrTexture = 0
    private var starProgram = 0
    private var useSprites = false
    private var debuggable = false
    private var sprites: StarSprites? = null // stars as sprites (needs the half-float buffer), else per pixel in earth.frag
    private var atmosphere = IntArray(0)
    private val textures = IntArray(9) // units: day, night, topography/specular, clouds, sky cells, sky stars, Milky Way, star colours, Moon
    private val locations = HashMap<Pair<Int, String>, Int>()
    private var dayAsset: String? = null
    private var camera: OrbitCamera? = null
    private var viewWidth = 1
    private var viewHeight = 1
    private var tanHalfX = 0f
    private var tanHalfY = 0f

    fun init() {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        debuggable = BuildConfig.DEBUG
        val forcedOff = debuggable && java.io.File(context.filesDir, "no_hdr").exists() // for A/B comparisons over adb
        useHdr = !forcedOff && ("GL_EXT_color_buffer_float" in extensions || "GL_EXT_color_buffer_half_float" in extensions)
        useSprites = useHdr && !(debuggable && java.io.File(context.filesDir, "no_sprites").exists())
        val defines = (if (useHdr) "#define HDR_OUT\n" else "") + (if (useSprites) "#define STAR_SPRITES\n" else "")
        program = buildProgram(readAsset("earth.vert"), readShader("earth.frag").replaceFirst(VERSION_LINE, "#version 300 es\n$defines"))
        if (useHdr) resolveProgram = buildProgram(readAsset("earth.vert"), readAsset("resolve.frag"))
        if (useSprites) starProgram = buildProgram(readShader("star.vert"), readShader("star.frag"))
        activate(program)
        listOf("uTransmittance", "uScatR", "uScatM", "uScatMS").forEachIndexed { i, name ->
            GLES30.glUniform1i(loc(name), ATMOSPHERE_UNIT + i)
        }
        listOf("uDay", "uNight", "uTopo", "uClouds", "uSkyCells", "uSkyStars", "uMilky", "uStarColors", "uMoon").forEachIndexed { unit, name ->
            GLES30.glUniform1i(loc(name), unit)
        }
        ensureSeason(System.currentTimeMillis())
        textures[1] = KtxLoader.load(context, "earth_night.ktx", 1)
        textures[2] = KtxLoader.load(context, "earth_topography_spec.ktx", 2)
        textures[3] = KtxLoader.load(context, "earth_clouds.ktx", 3)
        SkyData.load(context, 4, 5, 7, uploadStars = !useSprites).let { sky ->
            textures[4] = sky.textures[0]
            textures[5] = sky.textures[1]
            textures[7] = sky.textures[2]
            if (useSprites) sprites = StarSprites(sky.table).also { it.init() }
        }
        textures[6] = KtxLoader.load(context, "sky_milkyway.ktx", 6)
        textures[8] = KtxLoader.load(context, "moon.ktx", 8)
        atmosphere = AtmosphereData.load(context, ATMOSPHERE_UNIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    /** Snow line, ice and vegetation follow the season: the Blue Marble month of today. */
    private fun ensureSeason(nowMs: Long) {
        val asset = Seasons.dayAsset(nowMs)
        if (asset == dayAsset) return
        if (textures[0] != 0) GLES30.glDeleteTextures(1, intArrayOf(textures[0]), 0)
        textures[0] = KtxLoader.load(context, asset, 0)
        dayAsset = asset
    }

    fun resize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        updateTarget()
        sprites?.invalidate()
        // Fixed vertical field of view; the distance then follows from the aspect ratio so that the Earth fills
        // FILL_X of the width. It is computed once per surface size and stays constant: no zoom while orbiting.
        buildCamera()
    }

    /** The user's settings; may be called at any time on the GL thread. */
    fun applySettings(v: Prefs.Values) {
        fill = v.fill
        latitudeDeg = v.latitudeDeg
        periodSeconds = v.periodSeconds
        featureMask = v.skipMask
        starGain = v.starGain
        flashRate = v.flashRate
        cloudRate = v.cloudRate
        frameSeconds = 1.0 / v.fps
        orbitDirection = v.orbitDirection
        val scaleChanged = v.renderScale != renderScale
        renderScale = v.renderScale
        if (viewWidth > 1) {
            if (scaleChanged) updateTarget()
            buildCamera()
            sprites?.invalidate() // the star list belongs to the old camera position
        }
    }

    /** The cloud layer's drift against the ground in turns, from real time (a lap takes an hour at a rate of 1; negative: westward). */
    private fun cloudShift(nowMs: Long): Float {
        if (cloudRate == 0.0) return 0f
        val lap = Prefs.CLOUD_LAP_SECONDS / Math.abs(cloudRate)
        return (Math.signum(cloudRate) * ((nowMs / 1000.0) % lap) / lap).toFloat()
    }

    /** How far the camera turned about the Earth's north axis (radians, Earth-fixed frame) during the last frame. */
    private fun cameraTurn(orbit: OrbitCamera, jd: Double): Double {
        val now = orbit.pose(jd).position
        val before = orbit.pose(jd - frameSeconds / 86400.0).position
        var turn = Math.atan2(now[2], now[0]) - Math.atan2(before[2], before[0])
        while (turn > Math.PI) turn -= 2 * Math.PI
        while (turn < -Math.PI) turn += 2 * Math.PI
        return turn
    }

    /** Size of the offscreen picture: the screen's size times the user's render scale (only the half-float path can scale). */
    private fun updateTarget() {
        val scale = if (useHdr) renderScale else 1.0
        renderW = (viewWidth * scale).toInt().coerceAtLeast(2)
        renderH = (viewHeight * scale).toInt().coerceAtLeast(2)
        if (useHdr) createHdrTarget(renderW, renderH, smooth = scale < 1.0)
    }

    private fun buildCamera() {
        val tanY = OrbitCamera.tanHalfY()
        val tanX = tanY * viewWidth / viewHeight
        camera = OrbitCamera(OrbitCamera.distanceForFill(tanX, fill), latitudeDeg, periodSeconds, orbitDirection)
        tanHalfX = tanX.toFloat()
        tanHalfY = tanY.toFloat()
    }

    /** Returns false if nothing was drawn because the surface size is not known yet. */
    fun draw(nowMs: Long): Boolean {
        val orbit = camera ?: return false
        ensureSeason(nowMs)
        val jd = Astronomy.julianDate(nowMs)
        val sun = Astronomy.sunDirection(jd)
        val moon = Astronomy.moonDirection(jd)
        val pose = orbit.pose(jd)

        if (useHdr) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hdrFramebuffer)
        GLES30.glViewport(0, 0, renderW, renderH)
        activate(program)
        uniform3("uCamPos", pose.position)
        uniform3("uCamRight", pose.right)
        uniform3("uCamUp", pose.up)
        uniform3("uCamFwd", pose.forward)
        GLES30.glUniformMatrix3fv(loc("uSkyRot"), 1, false, SkyFrame.toUniform(SkyFrame.earthFixedToSky(jd, pose)), 0)
        GLES30.glUniform1f(loc("uCloudShift"), cloudShift(nowMs))
        GLES30.glUniform1f(loc("uStarGain"), starGain)
        GLES30.glUniform1f(loc("uFlashRate"), flashRate)
        GLES30.glUniform1f(loc("uSkyBlur"), cameraTurn(orbit, jd).toFloat())
        GLES30.glUniform1i(loc("uSkip"), skipMask or featureMask)
        GLES30.glUniform1f(loc("uAuroraPhase"), ((nowMs / 1000.0) % AURORA_PERIOD_SECONDS / AURORA_PERIOD_SECONDS).toFloat())
        GLES30.glUniform1f(loc("uTime"), ((nowMs / 1000.0) % 3600.0).toFloat())
        GLES30.glUniform1f(loc("uPixelAngle"), 2f * tanHalfX / renderW)
        GLES30.glUniform2f(loc("uTanHalf"), tanHalfX, tanHalfY)
        GLES30.glUniform3f(loc("uSunDir"), sun.x.toFloat(), sun.y.toFloat(), sun.z.toFloat())
        GLES30.glUniform3f(loc("uMoonDir"), moon.x.toFloat(), moon.y.toFloat(), moon.z.toFloat())
        val moonDistance = Astronomy.moonDistanceKm(jd) / EARTH_RADIUS_KM
        GLES30.glUniform3f(loc("uMoonPos"), (moon.x * moonDistance).toFloat(), (moon.y * moonDistance).toFloat(), (moon.z * moonDistance).toFloat())
        GLES30.glUniform1f(loc("uSolarEclipse"), if (Astronomy.mayShadeEarth(sun, moon)) 1f else 0f)
        GLES30.glUniform1f(loc("uLunarEclipse"), if (Astronomy.mayEnterEarthShadow(sun, moon)) 1f else 0f)
        val pole = Astronomy.eclipticPole(jd)
        GLES30.glUniform3f(loc("uEclipticPole"), pole.x.toFloat(), pole.y.toFloat(), pole.z.toFloat())
        GLES30.glUniform1f(loc("uMoonPhase"), Astronomy.moonPhaseFactor(sun, moon).toFloat())
        GLES30.glUniform1f(loc("uP3"), if (outputP3) 1f else 0f)
        GLES30.glUniform1f(loc("uDither"), 1f / ((1 shl outputBits) - 1))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        sprites?.let { if (skipMask and 1 == 0) drawStars(it, nowMs, jd, pose) }
        if (useHdr) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewWidth, viewHeight)
            activate(resolveProgram)
            GLES30.glUniform1i(loc("uHdr"), HDR_UNIT)
            GLES30.glUniform2f(loc("uScreen"), viewWidth.toFloat(), viewHeight.toFloat())
            GLES30.glUniform1f(loc("uP3"), if (outputP3) 1f else 0f)
            GLES30.glUniform1f(loc("uDither"), 1f / ((1 shl outputBits) - 1))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        return true
    }

    /** Adds the stars to the linear-light buffer: additive point sprites, hidden by the planet in the fragment shader. */
    private fun drawStars(stars: StarSprites, nowMs: Long, jd: Double, pose: OrbitCamera.Pose) {
        val sky = SkyFrame.earthFixedToSky(jd, pose)
        stars.update(nowMs, sky, pose.right, pose.up, pose.forward, tanHalfX.toDouble(), tanHalfY.toDouble())
        if (stars.rebuilt) {
            stars.rebuilt = false
            if (debuggable) android.util.Log.d("EarthWallpaper", "star list: ${stars.visibleCount} stars in %.1f ms".format(stars.lastBuildMs))
        }
        activate(starProgram)
        GLES30.glUniformMatrix3fv(loc("uSkyRot"), 1, false, SkyFrame.toUniform(sky), 0)
        uniform3("uCamPos", pose.position)
        uniform3("uCamRight", pose.right)
        uniform3("uCamUp", pose.up)
        uniform3("uCamFwd", pose.forward)
        GLES30.glUniform2f(loc("uTanHalf"), tanHalfX, tanHalfY)
        GLES30.glUniform2f(loc("uViewport"), renderW.toFloat(), renderH.toFloat())
        GLES30.glUniform1f(loc("uPixelAngle"), 2f * tanHalfX / renderW)
        GLES30.glUniform1f(loc("uStarGain"), starGain)
        GLES30.glUniform1f(loc("uRenderScale"), (renderW.toFloat() / viewWidth))
        GLES30.glUniform1f(loc("uTime"), ((nowMs / 1000.0) % 3600.0).toFloat())
        GLES30.glUniform1i(loc("uStarColors"), 7)
        GLES30.glUniform1i(loc("uTransmittance"), ATMOSPHERE_UNIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        stars.draw()
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        GLES30.glDeleteTextures(textures.size, textures, 0)
        GLES30.glDeleteTextures(atmosphere.size, atmosphere, 0)
        GLES30.glDeleteProgram(program)
        sprites?.release()
        if (useSprites) GLES30.glDeleteProgram(starProgram)
        if (useHdr) {
            GLES30.glDeleteProgram(resolveProgram)
            GLES30.glDeleteTextures(1, intArrayOf(hdrTexture), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(hdrFramebuffer), 0)
        }
    }

    private fun activate(id: Int) {
        activeProgram = id
        GLES30.glUseProgram(id)
    }

    /** The scene's linear light needs more than 8 bits and more than 1.0: a half-float buffer of the screen's size. */
    private fun createHdrTarget(width: Int, height: Int, smooth: Boolean) {
        if (hdrTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(hdrTexture), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(hdrFramebuffer), 0)
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        hdrTexture = ids[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + HDR_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hdrTexture)
        // 4 bytes per pixel instead of 8: R11G11B10F keeps about 1.5 % relative precision at any brightness
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R11F_G11F_B10F, width, height, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_INT_10F_11F_11F_REV, null)
        val filter = if (smooth) GLES30.GL_LINEAR else GLES30.GL_NEAREST // stretched: bilinear; same size: exact texels
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, ids, 0)
        hdrFramebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hdrFramebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, hdrTexture, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        check(complete) { "half-float framebuffer is not complete" }
    }

    private fun loc(name: String) = locations.getOrPut(activeProgram to name) { GLES30.glGetUniformLocation(activeProgram, name) }

    private fun uniform3(name: String, v: DoubleArray) =
        GLES30.glUniform3f(loc(name), v[0].toFloat(), v[1].toFloat(), v[2].toFloat())

    private fun readAsset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    /** A shader asset with its `#include "file"` lines replaced by that file. */
    private fun readShader(name: String): String = readAsset(name).lines().joinToString("\n") { line ->
        INCLUDE.matchEntire(line.trim())?.let { readShader(it.groupValues[1]) } ?: line
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Program link failed: " + GLES30.glGetProgramInfoLog(prog) }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(shader) }
        return shader
    }
}
