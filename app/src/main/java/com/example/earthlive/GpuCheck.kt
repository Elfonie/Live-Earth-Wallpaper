package com.example.earthlive

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30

/** Asks the GPU what the wallpaper needs, on a throw-away 1x1 surface. Can run on any thread. */
object GpuCheck {
    class Result(val gpu: String, val problems: List<String>) {
        val supported get() = problems.isEmpty()
    }

    private const val MIN_TEXTURE_SIZE = 4096 // the 4k maps; the 8k ones (clouds, Milky Way) load at 4k on a smaller GPU, see KtxLoader

    fun run(): Result {
        val problems = ArrayList<String>()
        var gpu = "unknown GPU"
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        try {
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) {
                return Result(gpu, listOf("No OpenGL display"))
            }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE),
                0, configs, 0, 1, count, 0,
            )
            val config = configs[0]
            if (count[0] == 0 || config == null) return Result(gpu, listOf("OpenGL ES 3.0 is not available"))
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return Result(gpu, listOf("OpenGL ES 3.0 could not be started"))
                gpu = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty().ifBlank { gpu }
                val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
                if ("GL_KHR_texture_compression_astc_ldr" !in extensions) problems += "The GPU has no ASTC texture compression"
                val size = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
                if (size[0] < MIN_TEXTURE_SIZE) problems += "The GPU's largest texture is ${size[0]} px (4096 needed)"
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } catch (e: Throwable) {
            problems += "GPU check failed: ${e.message}"
        } // no eglTerminate: the wallpaper may be running in this process on the same display
        return Result(gpu, problems)
    }
}
