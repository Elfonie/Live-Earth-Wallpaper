package com.example.earthlive

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stars on screen as point sprites. About 870,000 stars are in the catalog but only some 10,000 are in the field of
 * view; the sky is attached to the camera and only drifts slowly (see [SkyFrame]), so the list of visible stars stays
 * valid for many seconds. It is rebuilt on the CPU from the cell grid every [REBUILD_MS] with a generous margin.
 * Must be used on the GL thread.
 */
class StarSprites(private val table: SkyData.Table) {
    private var vao = 0
    private var vbo = 0
    private var count = 0
    private var builtAtMs = Long.MIN_VALUE
    var rebuilt = false // set by update() when it rebuilt the list; the caller may log it and clear it

    /** Number of stars in the current list, and how long the last rebuild took (for the debug log). */
    val visibleCount get() = count
    var lastBuildMs = 0.0
        private set

    private val cellRad = Math.PI / 720.0 // 0.25 degrees
    private val cosRa = DoubleArray(table.cellsW) { cos((it + 0.5) * cellRad) }
    private val sinRa = DoubleArray(table.cellsW) { sin((it + 0.5) * cellRad) }
    private val cosDec = DoubleArray(table.cellsH) { cos((it + 0.5) * cellRad - Math.PI / 2) }
    private val sinDec = DoubleArray(table.cellsH) { sin((it + 0.5) * cellRad - Math.PI / 2) }

    fun init() {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glGenBuffers(1, ids, 1)
        vbo = ids[1]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE, 16)
        GLES30.glBindVertexArray(0)
    }

    /** The list is valid for the current field of view only: call after the surface size changed. */
    fun invalidate() {
        builtAtMs = Long.MIN_VALUE
        count = 0 // build the next list right away: the old one was cut for another screen
    }

    private var scratch = FloatArray(32_768 * FLOATS) // one list at a time: the next build starts after the upload of this one

    private class Built(val data: FloatArray, val count: Int, val ms: Double)

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "star-list").apply { isDaemon = true } }
    @Volatile private var ready: Built? = null
    private var inFlight = false

    /**
     * Keeps the list fresh. When it is old a new one is built on a worker thread (some 50-150 ms of CPU work, which
     * must not stall a frame) and uploaded by a later call; the old list stays in use meanwhile, its margin covers
     * the time. The very first list is built right away. [sky] is the Earth-fixed to J2000 rotation (row-major, as
     * [SkyFrame] gives it), the camera basis is in the Earth-fixed frame.
     */
    fun update(nowMs: Long, sky: DoubleArray, right: DoubleArray, up: DoubleArray, forward: DoubleArray, tanHalfX: Double, tanHalfY: Double) {
        ready?.let { built ->
            ready = null
            inFlight = false
            upload(built)
        }
        if (inFlight || Math.abs(nowMs - builtAtMs) < REBUILD_MS) return
        builtAtMs = nowMs
        val skyCopy = sky.clone()
        val rightCopy = right.clone()
        val upCopy = up.clone()
        val forwardCopy = forward.clone()
        if (count == 0) {
            upload(build(skyCopy, rightCopy, upCopy, forwardCopy, tanHalfX, tanHalfY))
        } else {
            inFlight = true
            worker.execute { ready = build(skyCopy, rightCopy, upCopy, forwardCopy, tanHalfX, tanHalfY) }
        }
    }

    private var staging: ByteBuffer? = null // grows when a list is bigger than any before, so rebuilds make no garbage

    private fun upload(built: Built) {
        count = built.count
        lastBuildMs = built.ms
        val size = built.count * STRIDE
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        var bytes = staging
        if (bytes == null || bytes.capacity() < size) {
            bytes = ByteBuffer.allocateDirect(size + size / 4).order(ByteOrder.nativeOrder())
            staging = bytes
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes.capacity(), null, GLES30.GL_DYNAMIC_DRAW)
        }
        bytes.clear()
        bytes.asFloatBuffer().put(built.data, 0, built.count * FLOATS)
        bytes.limit(size)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, size, bytes)
        rebuilt = true
    }

    /** CPU only, safe on any thread. */
    private fun build(sky: DoubleArray, right: DoubleArray, up: DoubleArray, forward: DoubleArray, tanHalfX: Double, tanHalfY: Double): Built {
        val started = System.nanoTime()
        // The camera axes in J2000: a star at J2000 direction s is at x = s.R / (s.F tanX), y = s.U / (s.F tanY)
        val r = rotate(sky, right)
        val u = rotate(sky, up)
        val f = rotate(sky, forward)

        val cells = table.cells.asIntBuffer()
        var out = scratch
        var n = 0
        val bounds = cellBounds(sky, right, up, forward, tanHalfX, tanHalfY)
        for (cy in bounds.rowFrom..bounds.rowTo) {
            val cd = cosDec[cy]
            val sd = sinDec[cy]
            for (i in 0 until bounds.columns) {
                val cx = (bounds.columnFrom + i) % table.cellsW
                val cell = cy * table.cellsW + cx
                val starCount = cells.get(cell * 2 + 1)
                if (starCount == 0) continue
                val sx = cd * cosRa[cx]
                val sy = cd * sinRa[cx]
                val depth = sx * f[0] + sy * f[1] + sd * f[2]
                if (depth <= 0.0) continue
                val x = (sx * r[0] + sy * r[1] + sd * r[2]) / (depth * tanHalfX)
                val y = (sx * u[0] + sy * u[1] + sd * u[2]) / (depth * tanHalfY)
                if (Math.abs(x) > 1.0 + MARGIN || Math.abs(y) > 1.0 + MARGIN) continue
                val first = cells.get(cell * 2)
                for (k in 0 until starCount) {
                    if ((n + 1) * FLOATS > out.size) out = out.copyOf(out.size * 2)
                    n = addStar(out, n, first + k, cx, cy)
                }
            }
        }
        scratch = out
        return Built(out, n, (System.nanoTime() - started) / 1e6)
    }

    fun draw() {
        if (count == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        worker.shutdownNow()
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    /** Record layout as in tools/make_sky.py: x = fraction in RA | fraction in Dec << 16, y = magnitude | colour << 8 | seed << 16. */
    private fun addStar(out: FloatArray, at: Int, index: Int, cx: Int, cy: Int): Int {
        val x = table.stars.getInt(index * 8)
        val y = table.stars.getInt(index * 8 + 4)
        val ra = (cx + (x and 0xFFFF) / 65535.0) * cellRad
        val dec = (cy + (x ushr 16) / 65535.0) * cellRad - Math.PI / 2
        val cd = cos(dec)
        var o = at * FLOATS
        out[o++] = (cd * cos(ra)).toFloat()
        out[o++] = (cd * sin(ra)).toFloat()
        out[o++] = sin(dec).toFloat()
        out[o++] = (y and 255) / 16f - 2f // magnitude
        out[o++] = ((y ushr 8) and 255).toFloat() // colour index
        out[o] = (y ushr 16).toFloat() // flicker seed
        return at + 1
    }

    private class Bounds(val rowFrom: Int, val rowTo: Int, val columnFrom: Int, val columns: Int)

    /**
     * The block of cells that can hold a visible star: the field of view's outline (a grid of rays over the screen,
     * a margin on top) turned into J2000 declination and right ascension, so that only some thousand of the million
     * cells are looked at. A view that contains a celestial pole covers every right ascension.
     */
    private fun cellBounds(sky: DoubleArray, right: DoubleArray, up: DoubleArray, forward: DoubleArray, tanX: Double, tanY: Double): Bounds {
        val steps = 8
        var decMin = Double.MAX_VALUE
        var decMax = -Double.MAX_VALUE
        val ras = DoubleArray((steps + 1) * (steps + 1))
        var k = 0
        for (i in 0..steps) {
            for (j in 0..steps) {
                val x = (i / steps.toDouble() * 2 - 1) * (1 + MARGIN)
                val y = (j / steps.toDouble() * 2 - 1) * (1 + MARGIN)
                val w = DoubleArray(3) { forward[it] + x * tanX * right[it] + y * tanY * up[it] }
                val s = rotate(sky, w)
                val len = Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
                val dec = Math.asin(s[2] / len)
                decMin = minOf(decMin, dec)
                decMax = maxOf(decMax, dec)
                ras[k++] = Math.atan2(s[1], s[0])
            }
        }
        // a pole inside the (widened) outline: its direction seen from the camera lies inside the screen
        val poleInside = listOf(1.0, -1.0).any { sign ->
            val p = doubleArrayOf(0.0, 0.0, sign)
            val w = DoubleArray(3) { sky[0 * 3 + it] * p[0] + sky[1 * 3 + it] * p[1] + sky[2 * 3 + it] * p[2] } // F^T p
            val f = w[0] * forward[0] + w[1] * forward[1] + w[2] * forward[2]
            f > 0 && Math.abs((w[0] * right[0] + w[1] * right[1] + w[2] * right[2]) / (f * tanX)) < 1 + MARGIN &&
                Math.abs((w[0] * up[0] + w[1] * up[1] + w[2] * up[2]) / (f * tanY)) < 1 + MARGIN
        }
        val rowMargin = 2 // cells (0.5 degrees)
        var rowFrom = ((decMin + Math.PI / 2) / cellRad).toInt() - rowMargin
        var rowTo = ((decMax + Math.PI / 2) / cellRad).toInt() + rowMargin
        if (poleInside) {
            if (decMax > 0) rowTo = table.cellsH - 1 else rowFrom = 0
        }
        rowFrom = rowFrom.coerceIn(0, table.cellsH - 1)
        rowTo = rowTo.coerceIn(0, table.cellsH - 1)

        val centre = ras[ras.size / 2]
        var spanMin = 0.0
        var spanMax = 0.0
        for (ra in ras) {
            var d = ra - centre
            while (d > Math.PI) d -= 2 * Math.PI
            while (d < -Math.PI) d += 2 * Math.PI
            spanMin = minOf(spanMin, d)
            spanMax = maxOf(spanMax, d)
        }
        val worstDec = maxOf(Math.abs(decMin), Math.abs(decMax))
        val marginRa = (rowMargin * cellRad) / maxOf(Math.cos(worstDec), 0.05)
        if (poleInside || spanMax - spanMin + 2 * marginRa > 1.5 * Math.PI) return Bounds(rowFrom, rowTo, 0, table.cellsW)
        val from = Math.floor((centre + spanMin - marginRa) / cellRad).toInt()
        val columns = Math.ceil((spanMax - spanMin + 2 * marginRa) / cellRad).toInt() + 1
        return Bounds(rowFrom, rowTo, Math.floorMod(from, table.cellsW), minOf(columns, table.cellsW))
    }

    private fun rotate(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }

    private companion object {
        const val FLOATS = 6
        const val STRIDE = FLOATS * 4
        const val REBUILD_MS = 10_000L
        const val MARGIN = 0.15 // of the half screen: the sky drifts ~0.1 of it in the 10 s between rebuilds
    }
}
