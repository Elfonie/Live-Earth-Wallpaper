package com.example.earthlive

import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference values: Meeus, Astronomical Algorithms, examples 12.a, 25.a, 47.a and 48.a. */
class AstronomyTest {

    private fun jd(iso: String) = Astronomy.julianDate(Instant.parse(iso).toEpochMilli())

    @Test
    fun julianDateOfJ2000() {
        assertEquals(2451545.0, jd("2000-01-01T12:00:00Z"), 1e-9)
    }

    @Test
    fun gmstMatchesMeeus() {
        assertEquals(197.693195, Astronomy.gmstDeg(2446895.5), 0.001)
    }

    @Test
    fun sunPositionMatchesMeeus() {
        val sun = Astronomy.sunEquatorial(2448908.5) // 1992-10-13 0h
        assertEquals(198.38083, sun.raDeg, 0.02)
        assertEquals(-7.78507, sun.decDeg, 0.02)
    }

    @Test
    fun moonPositionMatchesMeeus() {
        val moon = Astronomy.moonEquatorial(2448724.5) // 1992-04-12 0h
        assertEquals(134.688470, moon.raDeg, 0.05)
        assertEquals(13.768368, moon.decDeg, 0.05)
    }

    @Test
    fun sunCulminatesOverWaldshutTiengenAtLocalNoon() {
        val lat = Math.toRadians(47.622)
        val lon = Math.toRadians(8.214)
        val site = Astronomy.Vec3(cos(lat) * cos(lon), sin(lat), -cos(lat) * sin(lon))
        fun elevationDeg(iso: String) = Math.toDegrees(asin(Astronomy.sunDirection(jd(iso)) dot site))

        // Solstice: 90 - 47.622 + 23.44 = 65.82 deg, reached at ~11:29 UTC (8.2 deg east, equation of time -2 min)
        assertEquals(65.82, elevationDeg("2026-06-21T11:29:00Z"), 0.1)
        assertTrue(elevationDeg("2026-06-21T23:29:00Z") < 0)
        // Symmetric before and after noon: confirms the east/west sign of the Earth-fixed frame
        assertEquals(elevationDeg("2026-06-21T05:29:00Z"), elevationDeg("2026-06-21T17:29:00Z"), 0.5)
    }

    @Test
    fun directionsAreUnitVectors() {
        val jd = jd("2026-10-20T12:00:00Z")
        for (v in listOf(Astronomy.sunDirection(jd), Astronomy.moonDirection(jd))) {
            assertEquals(1.0, v dot v, 1e-9)
        }
    }

    @Test
    fun moonPhaseFactorFullHalfNew() {
        val sun = Astronomy.Vec3(1.0, 0.0, 0.0)
        assertEquals(1.0, Astronomy.moonPhaseFactor(sun, Astronomy.Vec3(-1.0, 0.0, 0.0)), 1e-9) // full
        assertEquals(0.5.pow(2.5), Astronomy.moonPhaseFactor(sun, Astronomy.Vec3(0.0, 1.0, 0.0)), 1e-9) // half, ~0.18
        assertEquals(0.0, Astronomy.moonPhaseFactor(sun, Astronomy.Vec3(1.0, 0.0, 0.0)), 1e-9) // new
    }

    @Test
    fun moonPhaseFromRealPositionsMatchesMeeusIlluminatedFraction() {
        val jd = 2448724.5 // 1992-04-12: Meeus k = 0.6786 (geocentric approximation is ~0.001 off)
        val k = (1 - (Astronomy.sunDirection(jd) dot Astronomy.moonDirection(jd))) / 2
        assertEquals(0.6786, k, 0.005)
    }

    @Test
    fun earthTurns15DegreesPerHourOfSiderealTime() {
        val a = Astronomy.gmstDeg(jd("2026-10-20T08:00:00Z"))
        val b = Astronomy.gmstDeg(jd("2026-10-20T09:00:00Z"))
        assertEquals(15.0411, (b - a + 360.0) % 360.0, 0.001)
    }

    @Test
    fun moonDistanceMatchesMeeusExample() { // Meeus example 47.a: 1992 April 12, 0h TD, 368 409.7 km
        assertEquals(368_409.7, Astronomy.moonDistanceKm(2_448_724.5), 100.0)
    }

    @Test
    fun theSunLiesInTheEclipticPlane() {
        val t = jd("2026-10-20T10:00:00Z")
        assertEquals(1.0, Math.sqrt(Astronomy.eclipticPole(t) dot Astronomy.eclipticPole(t)), 1e-9)
        assertEquals(0.0, Astronomy.eclipticPole(t) dot Astronomy.sunDirection(t), 5e-4)
    }

    @Test
    fun theTotalSolarEclipseOfApril2024IsDetected() { // greatest eclipse 2024-04-08 18:17 UTC
        val t = jd("2024-04-08T18:17:00Z")
        val sun = Astronomy.sunDirection(t)
        val moon = Astronomy.moonDirection(t)
        assertTrue(Astronomy.mayShadeEarth(sun, moon))
        assertTrue(!Astronomy.mayEnterEarthShadow(sun, moon))
        val later = jd("2024-04-12T18:17:00Z")
        assertTrue(!Astronomy.mayShadeEarth(Astronomy.sunDirection(later), Astronomy.moonDirection(later)))
    }

    @Test
    fun theTotalLunarEclipseOfSeptember2025IsDetected() { // greatest eclipse 2025-09-07 18:11 UTC
        val t = jd("2025-09-07T18:11:00Z")
        assertTrue(Astronomy.mayEnterEarthShadow(Astronomy.sunDirection(t), Astronomy.moonDirection(t)))
        assertTrue(!Astronomy.mayShadeEarth(Astronomy.sunDirection(t), Astronomy.moonDirection(t)))
    }
}
