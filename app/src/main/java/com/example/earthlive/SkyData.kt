package com.example.earthlive

import android.content.Context
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uploads assets/sky_stars.bin (layout in tools/make_sky.py): the cell grid, the star table and the colour table. */
object SkyData {
    private const val MAGIC = 0x32594B53 // "SKY2"
    private const val ROW_TEXELS = 2048
    private const val COLOR_BYTES = 256 * 4

    /** The star table in memory: per cell (first star, count), per star (position fractions, magnitude/colour/seed). */
    class Table(val cells: ByteBuffer, val stars: ByteBuffer, val cellsW: Int, val cellsH: Int)

    class Loaded(val textures: IntArray, val table: Table)

    /**
     * Returns the texture ids (cells RG32UI, stars RG32UI, colours RGBA8), bound to the given units, and the table.
     * With [uploadStars] false the cell and star textures are left out (id 0): sprites do not need them on the GPU.
     */
    fun load(context: Context, cellUnit: Int, starUnit: Int, colorUnit: Int, uploadStars: Boolean): Loaded {
        val data = AssetMap.map(context, "sky_stars.bin")
        require(data.getInt(0) == MAGIC) { "sky_stars.bin has a wrong header" }
        val count = data.getInt(4)
        val cellsW = data.getInt(8)
        val cellsH = data.getInt(12)
        val cellBytes = cellsW * cellsH * 8
        val starRows = (count + ROW_TEXELS - 1) / ROW_TEXELS
        val starBytes = starRows * ROW_TEXELS * 8

        // a real slice (offset 0), so absolute reads on the table buffers start at the first byte of their part
        fun slice(from: Int, size: Int) = data.duplicate().apply {
            position(from)
            limit(from + size)
        }.slice().order(ByteOrder.LITTLE_ENDIAN)

        val ids = IntArray(3)
        if (uploadStars) {
            GLES30.glGenTextures(2, ids, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + cellUnit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG32UI, cellsW, cellsH, 0,
                GLES30.GL_RG_INTEGER, GLES30.GL_UNSIGNED_INT, slice(16, cellBytes),
            )
            setExact()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + starUnit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[1])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG32UI, ROW_TEXELS, starRows, 0,
                GLES30.GL_RG_INTEGER, GLES30.GL_UNSIGNED_INT, slice(16 + cellBytes, starBytes),
            )
            setExact()
        }
        GLES30.glGenTextures(1, ids, 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + colorUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[2])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 256, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, slice(16 + cellBytes + starBytes, COLOR_BYTES),
        )
        setExact()
        return Loaded(ids, Table(slice(16, cellBytes), slice(16 + cellBytes, starBytes), cellsW, cellsH))
    }

    /** Integer textures are only complete with nearest filtering; the shader uses texelFetch. */
    private fun setExact() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }
}
