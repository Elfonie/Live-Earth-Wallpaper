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
    fun aNewPeriodKeepsTheCameraWhereItIs() {
        val old = OrbitCamera(distance, OrbitCamera.LATITUDE_DEG, 24 * 60.0)
        val next = OrbitCamera(distance, OrbitCamera.LATITUDE_DEG, 7 * 60.0, direction = -1)
        next.phase = old.angle(start) - next.angle(start) // what EarthRenderer.buildCamera does
        val a = old.inertialPosition(start)
        val b = next.inertialPosition(start)
        for (i in 0..2) assertEquals(a[i], b[i], 1e-9)
        assertTrue("and then it flies at the new rate", old.inertialPosition(start + 1 / 1440.0)[1] != next.inertialPosition(start + 1 / 1440.0)[1])
    }

    @Test
    fun aPoseAtAnotherLatitudeIsTheOneOfACameraThere() {
        // the stars are placed with the pose at the default latitude, whatever latitude the camera has
        val high = OrbitCamera(distance, 60.0)
        val there = high.pose(start, OrbitCamera.LATITUDE_DEG)
        val own = OrbitCamera(distance, OrbitCamera.LATITUDE_DEG).pose(start)
        for (i in 0..2) {
            assertEquals(own.position[i], there.position[i], 1e-9)
            assertEquals(own.up[i], there.up[i], 1e-9)
        }
        assertTrue("and the camera's own latitude is kept", high.pose(start).position[1] > there.position[1])
    }

    @Test
    fun distanceStaysConstantOverTheWholeOrbit() {
        for (minute in 0 until 600 step 7) {
            assertEquals(distance, length(camera.pose(start + minute / 1440.0).position), 1e-9)
        }
    }

    @Test
    fun theOrbitSpeedFollowsTheSettingsPeriod() {
        // the camera turns 360 degrees per period about the polar axis; seen from the Earth's centre its position moves by
        // that angle times cos(latitude), plus or minus the Earth's own turning (0.0042 degrees per second, times cos(latitude))
        for (minutes in intArrayOf(1, 6, 12, 24, 60, 120, 240)) {
            val orbit = OrbitCamera(distance, OrbitCamera.LATITUDE_DEG, minutes * 60.0)
            val a = orbit.pose(start).position
            val b = orbit.pose(start + 10.0 / 86400.0).position
            val degreesPerSecond = Math.toDegrees(kotlin.math.acos(dot(a, b) / (length(a) * length(b)))) / 10.0
            val expected = 360.0 / (minutes * 60.0) * kotlin.math.cos(Math.toRadians(OrbitCamera.LATITUDE_DEG))
            assertEquals("period $minutes min", expected, degreesPerSecond, expected * 0.03 + 0.0042)
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
