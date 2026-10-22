package com.example.earthlive

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A camera that circles the Earth at a fixed latitude around the Earth's centre, looking at the centre with the Earth's axis as "up".
 * The orbit is fixed in the inertial frame and the position is expressed in the renderer's Earth-fixed frame, so the
 * Earth rotates, the Sun and Moon move and the seasons turn exactly as the real UTC time says; only the orbit itself
 * is flown much faster than a real satellite, so that the motion is visible.
 */
class OrbitCamera(
    private val distance: Double,
    var latitudeDeg: Double = LATITUDE_DEG, // the renderer eases it towards a new setting
    private val periodSeconds: Double = PERIOD_SECONDS,
    private val direction: Int = 1, // -1 flies the orbit the other way round
) {
    /** Added to the angle on the orbit: set when a setting changes, so that the camera stays where it is. */
    var phase = 0.0

    /** Angle on the orbit in radians (0 towards the vernal equinox). */
    fun angle(jd: Double): Double = direction * ((jd - J2000) * 86400.0 % periodSeconds) / periodSeconds * 2 * PI + phase

    /** Camera position (Earth radii) and orthonormal view basis in the Earth-fixed frame. */
    class Pose(val position: DoubleArray, val forward: DoubleArray, val right: DoubleArray, val up: DoubleArray)

    /** Position on the orbit in the inertial equatorial frame (x to the vernal equinox, z to the pole). */
    fun inertialPosition(jd: Double, latitude: Double = latitudeDeg): DoubleArray {
        val u = angle(jd)
        val lat = Math.toRadians(latitude)
        return doubleArrayOf(distance * cos(lat) * cos(u), -distance * cos(lat) * sin(u), distance * sin(lat)) // westward: the surface slides from Africa towards Asia
    }

    /** The pose at [latitude] (the camera's own by default) on the same orbit angle: the sky is placed with the one at the default latitude. */
    fun pose(jd: Double, latitude: Double = latitudeDeg): Pose {
        val e = Astronomy.earthRotation(jd)
        val inertial = inertialPosition(jd, latitude)
        val position = DoubleArray(3) { e[it * 3] * inertial[0] + e[it * 3 + 1] * inertial[1] + e[it * 3 + 2] * inertial[2] }
        val toCamera = normalize(position)
        // The north pole direction, projected into the image plane
        val tilt = toCamera[1]
        val up = normalize(doubleArrayOf(-tilt * toCamera[0], 1.0 - tilt * toCamera[1], -tilt * toCamera[2]))
        val forward = normalize(DoubleArray(3) { -position[it] })
        return Pose(position, forward, cross(forward, up), up)
    }

    companion object {
        const val LATITUDE_DEG = 21.4 // the camera circles at this latitude (Mecca), so the view never leaves it
        const val PERIOD_SECONDS = 24 * 60.0 // a real orbit at this distance would take 41 hours
        const val FOV_Y_DEG = 30.0
        const val FILL_X = 0.92 // Earth's disc diameter as a fraction of the screen width
        private const val J2000 = 2451545.0

        const val CLOUD_DRIFT_SECONDS = 3600.0 // the clouds go once round the Earth, west to east, in this time

        /** Drift of the cloud layer against the ground in turns [0, 1), from real time (so it also never jumps). */
        fun cloudShift(epochMs: Long): Float = ((epochMs / 1000.0) % CLOUD_DRIFT_SECONDS / CLOUD_DRIFT_SECONDS).toFloat()

        fun tanHalfY() = tan(Math.toRadians(FOV_Y_DEG / 2))

        /** Distance (Earth radii) at which the sphere's silhouette spans [fill] of the screen width. */
        fun distanceForFill(tanHalfX: Double, fill: Double = FILL_X): Double = 1.0 / sin(atan(fill * tanHalfX))

        private fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        private fun normalize(v: DoubleArray): DoubleArray {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return DoubleArray(3) { v[it] / n }
        }
    }
}
