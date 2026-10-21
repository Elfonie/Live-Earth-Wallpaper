package com.example.earthlive

import java.time.Instant
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitCameraTest {
    private val tanHalfX = OrbitCamera.tanHalfY() * 1280.0 / 2856.0 // Pixel 10 Pro
    private val distance = OrbitCamera.distanceForFill(tanHalfX)
    private val camera = OrbitCamera(distance)
    private val start = Astronomy.julianDate(Instant.parse("2026-10-20T08:00:00Z").toEpochMilli())

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun length(a: DoubleArray) = sqrt(dot(a, a))

    @Test
    fun earthFillsNinetyTwoPercentOfTheScreenWidth() {
        val silhouetteNdc = tan(asin(1.0 / distance)) / tanHalfX
        assertEquals(0.92, silhouetteNdc, 1e-9)
        assertTrue("well outside the planet, a long lens", distance in 5.0..15.0)
    }

    @Test
    fun distanceStaysConstantOverTheWholeOrbit() {
        for (minute in 0 until 600 step 7) {
            assertEquals(distance, length(camera.pose(start + minute / 1440.0).position), 1e-9)
        }
    }

    @Test
    fun reversedOrbitMirrorsTheMotionAndKeepsDistanceAndLatitude() {
        val reversed = OrbitCamera(distance, direction = -1)
        val t = start + 0.0123
        val a = camera.inertialPosition(t)
        val b = reversed.inertialPosition(t)
        assertEquals(a[0], b[0], 1e-9) // x and z stay, the sense of travel (y) flips
        assertEquals(-a[1], b[1], 1e-9)
        assertEquals(a[2], b[2], 1e-9)
    }

    @Test
    fun cameraStaysAtTheSameLatitude() {
        for (second in 0 until OrbitCamera.PERIOD_SECONDS.toInt() step 5) { // one full orbit
            val p = camera.pose(start + second / 86400.0).position
            assertEquals(OrbitCamera.LATITUDE_DEG, Math.toDegrees(asin(p[1] / length(p))), 1e-6)
        }
    }

    @Test
    fun cloudsDriftSteadilyAndWrapWithoutAJump() {
        val period = (OrbitCamera.CLOUD_DRIFT_SECONDS * 1000).toLong()
        assertEquals(0f, OrbitCamera.cloudShift(3 * period), 1e-6f)
        assertEquals(0.25f, OrbitCamera.cloudShift(3 * period + period / 4), 1e-6f)
        assertTrue(OrbitCamera.cloudShift(4 * period - 1) > 0.999f) // wraps to ~0 right after: the texture repeats, so no jump
    }

    @Test
    fun inertialPositionRepeatsAfterOnePeriod() {
        val a = camera.inertialPosition(start)
        val b = camera.inertialPosition(start + OrbitCamera.PERIOD_SECONDS / 86400.0)
        for (i in 0..2) assertEquals(a[i], b[i], 1e-6)
    }

    @Test
    fun cameraLooksAtTheCentreWithTheEarthAxisUp() {
        for (minute in 0 until 300 step 11) {
            val pose = camera.pose(start + minute / 1440.0)
            for (i in 0..2) assertEquals(-pose.position[i] / distance, pose.forward[i], 1e-9)
            assertEquals(0.0, dot(pose.up, pose.forward), 1e-9)
            assertEquals(0.0, dot(pose.right, pose.forward), 1e-9)
            assertEquals(0.0, dot(pose.right, pose.up), 1e-9)
            assertEquals(1.0, length(pose.right), 1e-9)
            assertTrue("north stays up on screen", pose.up[1] > 0.5)
        }
    }
}
