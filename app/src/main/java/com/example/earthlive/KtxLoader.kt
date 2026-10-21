package com.example.earthlive

import android.content.Context
import android.opengl.GLES30
import java.nio.ByteOrder
import kotlin.math.max

/** Uploads a KTX1 file with ASTC-compressed mip levels (as written by tools/make_textures.py) to texture unit `unit`. */
object KtxLoader {
    private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE

    fun load(context: Context, asset: String, unit: Int): Int {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        check("GL_KHR_texture_compression_astc_ldr" in extensions) { "ASTC LDR is not supported by this GPU" }

        val data = AssetMap.map(context, asset)

        require(data.getInt(0) == 0x58544BAB && data.getInt(4) == 0xBB313120.toInt() && data.getInt(8) == 0x0A1A0A0D) {
            "$asset is not a KTX1 file"
        }
        require(data.getInt(12) == 0x04030201) { "$asset has unexpected endianness" }
        val format = data.getInt(28)
        val width = data.getInt(36)
        val height = data.getInt(40)
        val levels = data.getInt(56)
        var offset = 64 + data.getInt(60) // header + key/value data

        val id = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        for (level in 0 until levels) {
            val size = data.getInt(offset)
            offset += 4
            val slice = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
                position(offset)
                limit(offset + size)
            }
            GLES30.glCompressedTexImage2D(
                GLES30.GL_TEXTURE_2D, level, format,
                max(1, width shr level), max(1, height shr level), 0, size, slice,
            )
            offset += (size + 3) and 3.inv()
        }
        setSampling(anisotropic = "GL_EXT_texture_filter_anisotropic" in extensions)
        return id
    }

    /** Trilinear, wrapping in longitude, clamped at the poles. */
    fun setSampling(anisotropic: Boolean) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (anisotropic) {
            GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, 8f)
        }
    }
}
