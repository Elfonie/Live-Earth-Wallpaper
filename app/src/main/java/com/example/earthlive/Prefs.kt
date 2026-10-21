package com.example.earthlive

import android.content.Context
import android.content.SharedPreferences

/** The user's settings (one preferences file shared by the wallpaper service and the settings page) and the last start error. */
object Prefs {
    const val FILE = "earth_live"
    const val EARTH_SIZE = "earth_size"      // percent of the screen width, 70..100
    const val ORBIT_MINUTES = "orbit_minutes" // one lap around the Earth, 6..60
    const val LATITUDE = "latitude"          // degrees, -60..60: the camera circles at this latitude
    const val FPS = "fps"                    // 10, 20 or 30
    const val LAST_ERROR = "last_error"

    const val DEFAULT_SIZE = 92
    const val DEFAULT_ORBIT = 24
    const val DEFAULT_LATITUDE = 21.4f
    const val DEFAULT_FPS = 10
    val FPS_CHOICES = intArrayOf(10, 20, 30)

    /** Feature switch key -> the SKIP_* bit of earth.frag that is set while the feature is off. */
    val FEATURES: Map<String, Int> = linkedMapOf(
        "aurora" to 16, "lightning" to 2048, "airglow" to 1024, "cirrus" to 4096, "clouds" to 256, "city_lights" to 512,
    )

    class Values(val fill: Double, val periodSeconds: Double, val latitudeDeg: Double, val fps: Int, val skipMask: Int)

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun skipMask(enabled: (String) -> Boolean): Int = FEATURES.entries.filter { !enabled(it.key) }.fold(0) { mask, e -> mask or e.value }

    fun read(prefs: SharedPreferences) = Values(
        fill = prefs.getInt(EARTH_SIZE, DEFAULT_SIZE).coerceIn(70, 100) / 100.0,
        periodSeconds = prefs.getInt(ORBIT_MINUTES, DEFAULT_ORBIT).coerceIn(6, 60) * 60.0,
        latitudeDeg = prefs.getFloat(LATITUDE, DEFAULT_LATITUDE).coerceIn(-60f, 60f).toDouble(),
        fps = prefs.getInt(FPS, DEFAULT_FPS).takeIf { it in FPS_CHOICES } ?: DEFAULT_FPS,
        skipMask = skipMask { prefs.getBoolean(it, true) },
    )

    fun reset(prefs: SharedPreferences) {
        prefs.edit().apply { listOf(EARTH_SIZE, ORBIT_MINUTES, LATITUDE, FPS).plus(FEATURES.keys).forEach(::remove) }.apply()
    }

    fun setError(context: Context, text: String?) {
        get(context).edit().apply { if (text == null) remove(LAST_ERROR) else putString(LAST_ERROR, text) }.apply()
    }
}
