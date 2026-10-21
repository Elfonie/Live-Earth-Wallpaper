package com.example.earthlive

import java.time.Instant
import kotlin.math.acos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyFrameTest {
    private val camera = OrbitCamera(OrbitCamera.distanceForFill(OrbitCamera.tanHalfY() * 1280.0 / 2856.0))
    private val j2000 = 2451545.0 // the drift angle is zero at J2000 and after every full drift period
    private val period = SkyFrame.DRIFT_PERIOD_SECONDS / 86400.0

    private fun apply(m: DoubleArray, v: DoubleArray) =
        DoubleArray(3) { m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2] }

    private fun jd(iso: String) = Astronomy.julianDate(Instant.parse(iso).toEpochMilli())
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    @Test
    fun theViewStartsAlongTheMilkyWay() {
        val t = j2000 + 40 * period // a drift angle of zero again
        val pose = camera.pose(t)
        val (l, b) = SkyFrame.galactic(apply(SkyFrame.earthFixedToSky(t, pose), pose.forward))
        assertEquals(SkyFrame.VIEW_GALACTIC_LONGITUDE_DEG, l, 1e-4)
        assertEquals(0.0, b, 1e-4)
    }

    @Test
    fun theMatrixIsARotation() {
        val t = jd("2026-10-20T08:07:00Z")
        val m = SkyFrame.earthFixedToSky(t, camera.pose(t))
        for (i in 0..2) for (j in 0..2) {
            val d = (0..2).sumOf { m[it * 3 + i] * m[it * 3 + j] } // columns are orthonormal
            assertEquals(if (i == j) 1.0 else 0.0, d, 1e-9)
        }
    }

    @Test
    fun theSkyDoesNotFollowTheOrbit() {
        // Same drift angle, very different camera positions: the camera's forward ray sees the same sky
        val t1 = j2000 + 40 * period
        val t2 = t1 + 5 * period // a whole number of drift periods later
        val p1 = camera.pose(t1)
        val p2 = camera.pose(t2)
        assertTrue("different places on the orbit", dot(p1.position, p2.position) < 0.99 * dot(p1.position, p1.position))
        val s1 = apply(SkyFrame.earthFixedToSky(t1, p1), p1.forward)
        val s2 = apply(SkyFrame.earthFixedToSky(t2, p2), p2.forward)
        for (i in 0..2) assertEquals(s1[i], s2[i], 1e-6)
    }

    @Test
    fun theSkyTurnsOnceInOneDriftPeriodAndAQuarterTurnInAQuarter() {
        val t = j2000 + 40 * period
        val pose = camera.pose(t)
        val before = apply(SkyFrame.earthFixedToSky(t, pose), pose.forward)
        val quarter = apply(SkyFrame.earthFixedToSky(t + period / 4, pose), pose.forward)
        assertEquals(90.0, Math.toDegrees(acos(dot(before, quarter))), 1e-3)
        val full = apply(SkyFrame.earthFixedToSky(t + period, pose), pose.forward)
        for (i in 0..2) assertEquals(before[i], full[i], 1e-6)
    }

    @Test
    fun starsSlideLeftAsTimePasses() {
        // A star that is slightly to the right of the view centre moves to the centre later: it slides left
        val t = j2000 + 40 * period
        val pose = camera.pose(t)
        val right = DoubleArray(3) { pose.forward[it] + 0.01 * pose.right[it] }
        val star = apply(SkyFrame.earthFixedToSky(t, pose), right)
        val later = t + 30.0 / 86400.0 // 30 s of drift
        val seenLater = apply(SkyFrame.earthFixedToSky(later, pose), pose.forward)
        val before = apply(SkyFrame.earthFixedToSky(t, pose), pose.forward)
        val angleToStar = { v: DoubleArray -> acos(dot(v, star) / sqrt(dot(v, v) * dot(star, star))) }
        assertTrue("the view centre approaches the star on its right", angleToStar(seenLater) < angleToStar(before))
    }
}
