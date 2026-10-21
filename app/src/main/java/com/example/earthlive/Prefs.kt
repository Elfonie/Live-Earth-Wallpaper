package com.example.earthlive

import android.content.Context
import android.content.SharedPreferences

/** The user's settings (one preferences file shared by the wallpaper service and the settings page) and the last start error. */
object Prefs {
    const val FILE = "earth_live"
    const val EARTH_SIZE = "earth_size"           // percent of the screen width, 70..100
    const val ORBIT_MINUTES = "orbit_minutes"      // one lap around the Earth, 6..60
    const val LATITUDE = "latitude"               // degrees, -60..60: the camera circles at this latitude
    const val STAR_BRIGHTNESS = "star_brightness" // percent, 25..150
    const val FPS = "fps"                         // one of FPS_CHOICES
    const val RENDER_SCALE = "render_scale"       // percent of the screen's resolution the picture is drawn at, 50..100
    const val CLOUD_SPEED = "cloud_speed"         // index into CLOUD_LAP_SECONDS
    const val LAST_ERROR = "last_error"

    const val DEFAULT_SIZE = 92
    const val DEFAULT_ORBIT = 24
    const val DEFAULT_LATITUDE = 21.4f
    const val DEFAULT_STARS = 100
    const val DEFAULT_FPS = 10
    const val DEFAULT_RENDER_SCALE = 100
    const val DEFAULT_CLOUD_SPEED = 2
    val FPS_CHOICES = intArrayOf(5, 10, 20, 30)
    val CLOUD_LAP_SECONDS = intArrayOf(0, 7200, 3600, 1800) // frozen, slow, normal (1 h around the Earth), fast

    /** Feature switch key -> the SKIP_* bit of earth.frag that is set while the feature is off. */
    val FEATURES: Map<String, Int> = linkedMapOf(
        "aurora" to 16, "lightning" to 2048, "airglow" to 1024, "cirrus" to 4096, "clouds" to 256, "city_lights" to 512,
        "sharp_clouds" to 64,
    )

    class Values(
        val fill: Double,
        val periodSeconds: Double,
        val latitudeDeg: Double,
        val starGain: Float,
        val fps: Int,
        val renderScale: Double,
        val cloudLapSeconds: Int,
        val skipMask: Int,
    )

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun skipMask(enabled: (String) -> Boolean): Int = FEATURES.entries.filter { !enabled(it.key) }.fold(0) { mask, e -> mask or e.value }

    fun read(prefs: SharedPreferences) = Values(
        fill = prefs.getInt(EARTH_SIZE, DEFAULT_SIZE).coerceIn(70, 100) / 100.0,
        periodSeconds = prefs.getInt(ORBIT_MINUTES, DEFAULT_ORBIT).coerceIn(6, 60) * 60.0,
        latitudeDeg = prefs.getFloat(LATITUDE, DEFAULT_LATITUDE).coerceIn(-60f, 60f).toDouble(),
        starGain = prefs.getInt(STAR_BRIGHTNESS, DEFAULT_STARS).coerceIn(25, 150) / 100f,
        fps = prefs.getInt(FPS, DEFAULT_FPS).takeIf { it in FPS_CHOICES } ?: DEFAULT_FPS,
        renderScale = prefs.getInt(RENDER_SCALE, DEFAULT_RENDER_SCALE).coerceIn(50, 100) / 100.0,
        cloudLapSeconds = CLOUD_LAP_SECONDS[prefs.getInt(CLOUD_SPEED, DEFAULT_CLOUD_SPEED).coerceIn(0, CLOUD_LAP_SECONDS.size - 1)],
        skipMask = skipMask { prefs.getBoolean(it, true) },
    )

    fun reset(prefs: SharedPreferences) {
        prefs.edit().apply {
            listOf(EARTH_SIZE, ORBIT_MINUTES, LATITUDE, STAR_BRIGHTNESS, FPS, RENDER_SCALE, CLOUD_SPEED).plus(FEATURES.keys).forEach(::remove)
        }.apply()
    }

    fun setError(context: Context, text: String?) {
        get(context).edit().apply { if (text == null) remove(LAST_ERROR) else putString(LAST_ERROR, text) }.apply()
    }
}
