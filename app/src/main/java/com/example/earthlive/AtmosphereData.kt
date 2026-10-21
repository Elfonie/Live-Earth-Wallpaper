package com.example.earthlive

import android.content.Context
import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * The precomputed light scattering of the atmosphere (tools/make_atmosphere.py, looked up in assets/atmosphere.glsl):
 * a 256 x 64 half-float transmittance table and three 256 x 128 x 32 volumes (Rayleigh, Mie, multiple scattering) in
 * R11G11B10F, 12 MB on the GPU in all.
 */
object AtmosphereData {
    private const val TRANSMITTANCE_W = 256
    private const val TRANSMITTANCE_H = 64
    private const val SCATTER_W = 256
    private const val SCATTER_H = 128
    private const val SCATTER_D = 32

    /** Texture ids for transmittance, Rayleigh, Mie, multiple scattering, bound to firstUnit .. firstUnit + 3. */
    fun load(context: Context, firstUnit: Int): IntArray {
        val ids = IntArray(4).also { GLES30.glGenTextures(4, it, 0) }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + firstUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, TRANSMITTANCE_W, TRANSMITTANCE_H, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, read(context, "atmosphere_transmittance.bin"),
        )
        setSampling(GLES30.GL_TEXTURE_2D)

        listOf("atmosphere_scatter_r.bin", "atmosphere_scatter_m.bin", "atmosphere_scatter_ms.bin").forEachIndexed { i, name ->
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + firstUnit + 1 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, ids[1 + i])
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_R11F_G11F_B10F, SCATTER_W, SCATTER_H, SCATTER_D, 0,
                GLES30.GL_RGB, GLES30.GL_UNSIGNED_INT_10F_11F_11F_REV, read(context, name),
            )
            setSampling(GLES30.GL_TEXTURE_3D)
        }
        return ids
    }

    private fun read(context: Context, name: String): ByteBuffer = AssetMap.map(context, name)

    private fun setSampling(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (target == GLES30.GL_TEXTURE_3D) GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
    }
}
