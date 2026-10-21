package com.example.earthlive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which part of the real sky (J2000 catalog coordinates) appears behind the Earth.
 *
 * The sky is attached to the camera, not to inertial space: the orbit therefore does not sweep the stars across the
 * screen. It only turns slowly about the view's vertical axis ([DRIFT_PERIOD_SECONDS]) and starts out looking along
 * the Milky Way, so the band crosses the picture diagonally. This is a deliberate look, not astronomy: the Earth,
 * Sun and Moon stay physically exact, the stars are just a calm, real-looking backdrop.
 */
object SkyFrame {
    const val DRIFT_PERIOD_SECONDS = 3 * 3600.0
    const val VIEW_GALACTIC_LONGITUDE_DEG = 320.0 // towards Norma/Scorpius, rich in dust lanes
    const val BAND_TILT_DEG = 35.0 // angle of the Milky Way against the horizontal edge of the screen

    // Equatorial -> galactic rotation matrix (J2000), row-major; its transpose goes the other way
    private val TO_GALACTIC = doubleArrayOf(
        -0.0548755604, -0.8734370902, -0.4838350155,
        0.4941094279, -0.4448296300, 0.7469822445,
        -0.8676661490, -0.1980763734, 0.4559837762,
    )

    /** Galactic longitude/latitude (degrees) of an equatorial direction. */
    fun galactic(eq: DoubleArray): Pair<Double, Double> {
        val g = DoubleArray(3) { TO_GALACTIC[it * 3] * eq[0] + TO_GALACTIC[it * 3 + 1] * eq[1] + TO_GALACTIC[it * 3 + 2] * eq[2] }
        return Pair(Math.toDegrees(kotlin.math.atan2(g[1], g[0])).mod(360.0), Math.toDegrees(kotlin.math.asin(g[2])))
    }

    private fun fromGalactic(x: Double, y: Double, z: Double) =
        DoubleArray(3) { TO_GALACTIC[it] * x + TO_GALACTIC[3 + it] * y + TO_GALACTIC[6 + it] * z }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** Camera-view axes (right, up, forward) expressed in celestial coordinates at the start of the drift. */
    private val view: Array<DoubleArray> = run {
        val l = Math.toRadians(VIEW_GALACTIC_LONGITUDE_DEG)
        val forward = fromGalactic(cos(l), sin(l), 0.0)
        val pole = fromGalactic(0.0, 0.0, 1.0)
        val along = cross(pole, forward) // along the Milky Way; both vectors are unit and perpendicular
        val tilt = Math.toRadians(BAND_TILT_DEG)
        val up = DoubleArray(3) { cos(tilt) * pole[it] + sin(tilt) * along[it] }
        arrayOf(cross(forward, up), up, forward)
    }

    /**
     * Row-major 3x3 matrix taking a ray in the Earth-fixed frame to the celestial direction to look up in the
     * catalog: camera basis first, then the slow drift about the vertical axis, then the fixed view direction.
     */
    fun earthFixedToSky(jd: Double, pose: OrbitCamera.Pose): DoubleArray {
        val alpha = (jd - 2451545.0) * 86400.0 % DRIFT_PERIOD_SECONDS / DRIFT_PERIOD_SECONDS * 2 * PI
        val c = cos(alpha)
        val s = sin(alpha)
        val camera = arrayOf(pose.right, pose.up, pose.forward) // rows: Earth-fixed ray -> view coordinates
        // view coordinates (x right, y up, z forward) after the drift: x' = c x + s z, y' = y, z' = -s x + c z
        val drifted = arrayOf(
            DoubleArray(3) { c * camera[0][it] + s * camera[2][it] },
            camera[1],
            DoubleArray(3) { -s * camera[0][it] + c * camera[2][it] },
        )
        return DoubleArray(9) { i ->
            val row = i / 3 // celestial axis
            val col = i % 3 // Earth-fixed axis
            view[0][row] * drifted[0][col] + view[1][row] * drifted[1][col] + view[2][row] * drifted[2][col]
        }
    }

    /** The same matrix in column-major order, which is what glUniformMatrix3fv reads. */
    fun toUniform(rowMajor: DoubleArray) = FloatArray(9) { rowMajor[(it % 3) * 3 + it / 3].toFloat() }
}
