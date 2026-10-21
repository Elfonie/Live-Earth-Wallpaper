package com.example.earthlive

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun and Moon directions in the renderer's Earth-fixed frame:
 * x = (0 N, 0 E), y = north pole, z = (0 N, 90 W). East longitude lon, latitude lat -> (cos lat cos lon, sin lat, -cos lat sin lon).
 * Accuracy: sun ~0.01 deg, moon ~0.05 deg (truncated Meeus ch. 25 / 47), far below what a 1280 px globe can show.
 */
object Astronomy {
    private const val J2000 = 2451545.0

    class Vec3(val x: Double, val y: Double, val z: Double) {
        infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    }

    class Equatorial(val raDeg: Double, val decDeg: Double)

    /** UT is taken as UTC; the difference (<1 s) is irrelevant here. */
    fun julianDate(epochMs: Long): Double = epochMs / 86_400_000.0 + 2_440_587.5

    /** Greenwich mean sidereal time in degrees [0, 360). */
    fun gmstDeg(jd: Double): Double {
        val t = (jd - J2000) / 36525.0
        val deg = 280.46061837 + 360.98564736629 * (jd - J2000) + 0.000387933 * t * t - t * t * t / 38_710_000.0
        return deg.mod(360.0)
    }

    /** Apparent place of the Sun; the true RA includes the equation of time. */
    fun sunEquatorial(jd: Double): Equatorial {
        val n = jd - J2000
        val meanLon = 280.460 + 0.9856474 * n
        val g = Math.toRadians(357.528 + 0.9856003 * n)
        val lambda = Math.toRadians(meanLon + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        return Equatorial(
            Math.toDegrees(atan2(cos(eps) * sin(lambda), cos(lambda))).mod(360.0),
            Math.toDegrees(asin(sin(eps) * sin(lambda))),
        )
    }

    // Meeus table 47.A/47.B, largest terms only: coefficient (1e-6 deg), then multipliers of D, M, M', F.
    private class Term(val c: Int, val d: Int, val m: Int, val mp: Int, val f: Int)

    private val LONGITUDE = listOf(
        Term(6288774, 0, 0, 1, 0), Term(1274027, 2, 0, -1, 0), Term(658314, 2, 0, 0, 0),
        Term(213618, 0, 0, 2, 0), Term(-185116, 0, 1, 0, 0), Term(-114332, 0, 0, 0, 2),
        Term(58793, 2, 0, -2, 0), Term(57066, 2, -1, -1, 0), Term(53322, 2, 0, 1, 0),
        Term(45758, 2, -1, 0, 0), Term(-40923, 0, 1, -1, 0), Term(-34720, 1, 0, 0, 0),
        Term(-30383, 0, 1, 1, 0), Term(15327, 2, 0, 0, -2), Term(-12528, 0, 0, 1, 2),
        Term(10980, 0, 0, 1, -2),
    )
    private val LATITUDE = listOf(
        Term(5128122, 0, 0, 0, 1), Term(280602, 0, 0, 1, 1), Term(277693, 0, 0, 1, -1),
        Term(173237, 2, 0, 0, -1), Term(55413, 2, 0, -1, 1), Term(46271, 2, 0, -1, -1),
        Term(32573, 2, 0, 0, 1), Term(17198, 0, 0, 2, 1), Term(9266, 2, 0, 1, -1),
        Term(8822, 0, 0, 2, -1), Term(8216, 2, -1, 0, -1), Term(4324, 2, 0, -2, -1),
        Term(4200, 2, 0, 1, 1), Term(-3359, 2, 1, 0, -1),
    )

    // Meeus table 47.A, distance column (1e-3 km), largest terms only
    private val DISTANCE = listOf(
        Term(-20905355, 0, 0, 1, 0), Term(-3699111, 2, 0, -1, 0), Term(-2955968, 2, 0, 0, 0),
        Term(-569925, 0, 0, 2, 0), Term(48888, 0, 1, 0, 0), Term(-3149, 0, 0, 0, 2),
        Term(246158, 2, 0, -2, 0), Term(-152138, 2, -1, -1, 0), Term(-170733, 2, 0, 1, 0),
        Term(-204586, 2, -1, 0, 0), Term(-129620, 0, 1, -1, 0), Term(108743, 1, 0, 0, 0),
        Term(104755, 0, 1, 1, 0), Term(10321, 2, 0, 0, -2), Term(79661, 0, 0, 1, 2),
        Term(34782, 0, 0, 1, -2), Term(-23210, 4, 0, -1, 0), Term(-21636, 4, 0, -2, 0),
        Term(24208, 2, 1, -1, 0), Term(30824, 2, 1, 0, 0), Term(-8379, 1, 0, -1, 0),
        Term(-16675, 1, 1, 0, 0), Term(-12831, 2, -1, 1, 0), Term(-10445, 2, 0, 2, 0),
        Term(-11650, 4, 0, 0, 0), Term(14403, 2, 0, -3, 0), Term(-7003, 0, 1, -2, 0),
    )

    /** Distance from the centre of the Earth to the Moon in km (accurate to about 100 km). */
    fun moonDistanceKm(jd: Double): Double {
        val t = (jd - J2000) / 36525.0
        val d = Math.toRadians(297.8501921 + 445267.1114034 * t)
        val m = Math.toRadians(357.5291092 + 35999.0502909 * t)
        val mp = Math.toRadians(134.9633964 + 477198.8675055 * t)
        val f = Math.toRadians(93.2720950 + 483202.0175233 * t)
        val e = 1 - 0.002516 * t - 0.0000074 * t * t
        return 385000.56 + DISTANCE.sumOf { it.c * e.pow(abs(it.m)) * cos(it.d * d + it.m * m + it.mp * mp + it.f * f) } * 1e-3
    }

    fun moonEquatorial(jd: Double): Equatorial {
        val t = (jd - J2000) / 36525.0
        val meanLon = 218.3164477 + 481267.88123421 * t // L'
        val d = Math.toRadians(297.8501921 + 445267.1114034 * t)
        val m = Math.toRadians(357.5291092 + 35999.0502909 * t)
        val mp = Math.toRadians(134.9633964 + 477198.8675055 * t) // M'
        val f = Math.toRadians(93.2720950 + 483202.0175233 * t)
        val e = 1 - 0.002516 * t - 0.0000074 * t * t // Sun's eccentricity damps terms containing M

        fun sum(terms: List<Term>) = terms.sumOf { term ->
            val arg = term.d * d + term.m * m + term.mp * mp + term.f * f
            term.c * e.pow(abs(term.m)) * sin(arg)
        } * 1e-6

        val lambda = Math.toRadians(meanLon + sum(LONGITUDE))
        val beta = Math.toRadians(sum(LATITUDE))
        val eps = Math.toRadians(23.439291 - 0.0130042 * t)
        val ra = atan2(sin(lambda) * cos(eps) - tan(beta) * sin(eps), cos(lambda))
        val dec = asin(sin(beta) * cos(eps) + cos(beta) * sin(eps) * sin(lambda))
        return Equatorial(Math.toDegrees(ra).mod(360.0), Math.toDegrees(dec))
    }

    /** Subpoint longitude = RA - GMST, latitude = declination. */
    fun earthFixed(eq: Equatorial, gmstDeg: Double): Vec3 {
        val lon = Math.toRadians(eq.raDeg - gmstDeg)
        val dec = Math.toRadians(eq.decDeg)
        return Vec3(cos(dec) * cos(lon), sin(dec), -cos(dec) * sin(lon))
    }

    /** Equatorial -> Earth-fixed: x_f = cos g x + sin g y, y_f = z, z_f = sin g x - cos g y, with g = GMST. Row-major 3x3. */
    fun earthRotation(jd: Double): DoubleArray {
        val g = Math.toRadians(gmstDeg(jd))
        return doubleArrayOf(
            cos(g), sin(g), 0.0,
            0.0, 0.0, 1.0,
            sin(g), -cos(g), 0.0,
        )
    }

    fun sunDirection(jd: Double) = earthFixed(sunEquatorial(jd), gmstDeg(jd))

    /** North pole of the ecliptic (right ascension 18 h, declination 90 degrees - obliquity) in the Earth-fixed frame. */
    fun eclipticPole(jd: Double): Vec3 = earthFixed(Equatorial(270.0, 90.0 - 23.439291), gmstDeg(jd))

    private val ECLIPSE_COS = cos(Math.toRadians(2.2)) // the Moon's shadow can reach the Earth within about 1.5 degrees of the Sun

    /** The Moon is so close to the Sun's direction that its shadow may touch the Earth (a solar eclipse is possible). */
    fun mayShadeEarth(sun: Vec3, moon: Vec3) = (sun dot moon) > ECLIPSE_COS

    /** The Moon is so close to the anti-Sun direction that it may enter the Earth's shadow (a lunar eclipse is possible). */
    fun mayEnterEarthShadow(sun: Vec3, moon: Vec3) = -(sun dot moon) > ECLIPSE_COS

    fun moonDirection(jd: Double) = earthFixed(moonEquatorial(jd), gmstDeg(jd))

    /**
     * Relative brightness of the Moon: 1 at full, 0 at new, ~0.18 at half.
     * The phase angle i (Sun-Moon-Earth) has cos i = -(sun . moon) seen from Earth, so with the
     * brief's ((1 + cos)/2)^2.5 the sign must be flipped; otherwise new moon would come out brightest.
     */
    fun moonPhaseFactor(sun: Vec3, moon: Vec3): Double {
        val cosPhaseAngle = -(sun dot moon)
        return ((1.0 + cosPhaseAngle) * 0.5).pow(2.5)
    }
}
