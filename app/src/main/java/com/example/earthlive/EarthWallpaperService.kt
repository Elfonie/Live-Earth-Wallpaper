package com.example.earthlive

import android.content.SharedPreferences
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder

class EarthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = EarthEngine()

    /**
     * Fixed frame rate (the user's choice, 10 by default): while the wallpaper is visible a frame is drawn every 1/fps s, always, so the display
     * sees one steady content rate (also announced with Surface.setFrameRate) instead of bursts and pauses.
     * Frames are paced with Choreographer, which lands them on vsync boundaries (10 fps = every 12th vsync at
     * 120 Hz). When the wallpaper becomes invisible (screen off) the callback is cancelled: nothing runs at all.
     */
    private inner class EarthEngine : Engine() {
        private val glThread = HandlerThread("earth-gl").apply { start() }
        private val glHandler = Handler(glThread.looper)

        @Volatile private var active = false

        // Everything below is touched on glThread only
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var renderer: EarthRenderer? = null
        private var fixedTimeMs = 0L // debug builds: a file `fixed_time` freezes the scene's clock (for screenshot comparisons)

        private var choreographer: Choreographer? = null
        private var running = false
        private var lastFrameNanos = 0L
        private var targetNanos = 0L
        private val frameCallback = Choreographer.FrameCallback { nanos -> onFrame(nanos) }
        private var fps = Prefs.DEFAULT_FPS
        private val frameNanos get() = 1_000_000_000L / fps
        private var surface: Surface? = null
        private val prefs = Prefs.get(this@EarthWallpaperService)
        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> glHandler.post { applyPrefs() } }
        private val logTimings = BuildConfig.DEBUG

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(false)
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            glHandler.post { choreographer = Choreographer.getInstance() } // bound to the GL thread's looper
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (logTimings) Log.d(TAG, "visible=$visible")
            active = visible
            glHandler.post {
                if (visible) {
                    ensureRunning()
                } else {
                    choreographer?.removeFrameCallback(frameCallback)
                    running = false
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            glHandler.post { createGl(holder.surface) }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            glHandler.post {
                renderer?.resize(width, height)
                ensureRunning()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            glHandler.post { destroyGl() }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            glHandler.post {
                destroyGl()
                glThread.quitSafely()
            }
        }

        private fun ensureRunning() {
            if (running || !active || renderer == null || eglSurface == EGL14.EGL_NO_SURFACE) return
            running = true
            lastFrameNanos = 0L
            choreographer?.postFrameCallback(frameCallback)
        }

        private fun onFrame(frameTimeNanos: Long) {
            val r = renderer
            if (!active || r == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                running = false
                return
            }
            if (logTimings && lastFrameNanos != 0L) {
                Log.d(TAG, "cadence %.1f ms engine %x".format((frameTimeNanos - lastFrameNanos) / 1e6, System.identityHashCode(this)))
            }
            lastFrameNanos = frameTimeNanos
            drawFrame(r)

            // Next frame one interval after this vsync. Posting a little early makes the callback fire on the very
            // vsync that is one interval away; if drawing took longer than an interval, resync instead of piling up.
            targetNanos = if (targetNanos == 0L || System.nanoTime() > targetNanos + frameNanos) {
                frameTimeNanos + frameNanos
            } else {
                targetNanos + frameNanos
            }
            val delayMs = ((targetNanos - System.nanoTime()) / 1_000_000L - VSYNC_SLACK_MS).coerceAtLeast(0L)
            choreographer?.postFrameCallbackDelayed(frameCallback, delayMs)
        }

        private fun drawFrame(r: EarthRenderer) {
            if (logTimings) debugControl(r)
            val start = SystemClock.elapsedRealtimeNanos()
            if (!r.draw(if (fixedTimeMs != 0L) fixedTimeMs else System.currentTimeMillis())) return // surface size not known yet
            if (logTimings) {
                GLES30.glFinish()
                Log.d(TAG, "frame %.1f ms".format((SystemClock.elapsedRealtimeNanos() - start) / 1e6))
            }
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        /**
         * Debug builds only, driven from adb: a file `skip_mask` in the app's files directory holds the SKIP_* bits to
         * switch off (for measuring battery current per feature), and a file `profile.flag` starts [profile] once.
         */
        private fun debugControl(r: EarthRenderer) {
            val dir = this@EarthWallpaperService.filesDir
            r.skipMask = java.io.File(dir, "skip_mask").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
            fixedTimeMs = java.io.File(dir, "fixed_time").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val flag = java.io.File(dir, "profile.flag")
            if (flag.exists()) {
                flag.delete()
                profile(r)
            }
        }

        /** Times every feature on its own: busy-loops frames (no swap, so no vsync cap) and logs medians per scene. */
        private fun profile(r: EarthRenderer) {
            val scenes = listOf(
                "day Europe" to "2026-10-20T10:06:30Z",
                "night, aurora and Sun" to "2026-10-20T10:11:08Z",
                "Moon in view" to "2026-10-22T00:06:38Z",
                "Pacific" to "2026-10-20T09:10:00Z",
            )
            val variants = listOf(
                "everything on" to 0, "no stars" to 1, "no Milky Way" to 2, "no atmosphere (back)" to 4,
                "no atmosphere (front)" to 8, "no aurora" to 16, "no Sun and Moon" to 32, "1 cloud tap instead of 4" to 64,
                "no cloud relief" to 128, "no clouds" to (256 or 128), "no city lights" to 512, "no airglow and noctilucent clouds" to 1024, "no lightning" to 2048,
                "nothing but the planet and sky" to 4095,
            )
            Log.i(TAG, "PROFILE start: ${scenes.size} scenes x ${variants.size} variants")
            for ((scene, time) in scenes) {
                val nowMs = java.time.Instant.parse(time).toEpochMilli()
                // GPU clocks and temperature drift, so the variants take turns frame by frame: every one of them sees the
                // same conditions. Median of 80 rounds.
                r.skipMask = 0
                repeat(40) { r.draw(nowMs); GLES30.glFinish() }
                val samples = variants.map { ArrayList<Double>() }
                val shuffler = java.util.Random(1)
                repeat(80) {
                    // a random order each round: a cheap frame lets the GPU clock drop, which would otherwise always hit
                    // the variant that follows it
                    variants.indices.shuffled(shuffler).forEach { index ->
                        r.skipMask = variants[index].second
                        val t0 = SystemClock.elapsedRealtimeNanos()
                        r.draw(nowMs)
                        GLES30.glFinish()
                        samples[index].add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                    }
                }
                val medians = variants.mapIndexed { index, (name, _) -> name to samples[index].sorted().let { it[it.size / 2] } }
                val full = medians.first().second
                for ((name, ms) in medians) Log.i(TAG, "PROFILE | %-22s | %-32s | %6.1f ms | %+6.1f ms".format(scene, name, ms, ms - full))
            }
            r.skipMask = 0
            Log.i(TAG, "PROFILE done")
        }

        /** Settings changed (or the first read): frame rate here, the picture in the renderer. */
        private fun applyPrefs() {
            val v = Prefs.read(prefs)
            if (v.fps != fps) {
                fps = v.fps
                targetNanos = 0L
                surface?.let(::announceFrameRate)
            }
            renderer?.applySettings(v)
        }

        private fun createGl(surface: Surface) {
            try {
                this.surface = surface
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
                val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()

                val config10 = chooseConfig(10)
                val config = config10 ?: chooseConfig(8) ?: error("No EGL config with OpenGL ES 3.0")
                eglContext = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )

                val p3Available = "EGL_KHR_gl_colorspace" in extensions &&
                    "EGL_EXT_gl_colorspace_display_p3_passthrough" in extensions
                if (p3Available) {
                    eglSurface = EGL14.eglCreateWindowSurface(
                        display, config, surface,
                        intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT, EGL14.EGL_NONE), 0,
                    )
                }
                val p3 = eglSurface != EGL14.EGL_NO_SURFACE
                if (!p3) {
                    eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                }
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
                Log.i(TAG, "EGL: ${if (config10 != null) 10 else 8}-bit, Display P3 passthrough: $p3")
                fps = Prefs.read(prefs).fps
                announceFrameRate(surface)

                renderer = EarthRenderer(this@EarthWallpaperService, p3, if (config10 != null) 10 else 8)
                    .also { it.applySettings(Prefs.read(prefs)) }
                    .also { it.init() }
                Prefs.setError(this@EarthWallpaperService, null)
            } catch (e: Throwable) { // the start page shows why the picture stays black
                Log.e(TAG, "GL setup failed", e)
                Prefs.setError(this@EarthWallpaperService, "${e.javaClass.simpleName}: ${e.message}".take(600))
                destroyGl()
            }
        }

        /** Tells the display pipeline the content rate, so it can pick a matching refresh rate and keep it. */
        private fun announceFrameRate(surface: Surface) {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    surface.setFrameRate(
                        fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                    )
                } else if (Build.VERSION.SDK_INT >= 30) {
                    surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "setFrameRate rejected", e)
            }
        }

        private fun chooseConfig(bits: Int): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, bits,
                    EGL14.EGL_GREEN_SIZE, bits,
                    EGL14.EGL_BLUE_SIZE, bits,
                    EGL14.EGL_ALPHA_SIZE, if (bits == 10) 2 else 8,
                    EGL14.EGL_DEPTH_SIZE, 0,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            )
            return if (count[0] > 0) configs[0] else null
        }

        private fun destroyGl() {
            choreographer?.removeFrameCallback(frameCallback)
            running = false
            targetNanos = 0L
            renderer?.release()
            renderer = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            }
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
        }
    }

    private companion object {
        const val TAG = "EarthWallpaper"
        const val VSYNC_SLACK_MS = 4L
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490
    }
}
