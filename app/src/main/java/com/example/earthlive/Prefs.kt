package com.example.earthlive

import android.content.Context
import android.content.SharedPreferences

/** The user's settings (one preferences file shared by the wallpaper service and the settings page) and the last start error. */
object Prefs {
    const val FILE = "earth_live"
    const val EARTH_SIZE = "earth_size"           // percent of the screen width, 70..100
    const val ORBIT_MINUTES = "orbit_minutes"      // one lap around the Earth, 1..120
    const val LATITUDE = "latitude"               // degrees, -60..60: the camera circles at this latitude
    const val STAR_BRIGHTNESS = "star_brightness" // percent, 25..150
    const val FPS = "fps"                         // one of FPS_CHOICES
    const val RENDER_SCALE = "render_scale"       // percent of the screen's resolution the picture is drawn at, 50..100
    const val CLOUD_SPEED = "cloud_speed_percent" // 0..300; 100 = the clouds go once around the Earth in an hour
    const val CLOUD_DIRECTION = "cloud_direction" // 0 towards the east (as in reality), 1 towards the west
    const val ORBIT_DIRECTION = "orbit_direction" // 0: the surface moves from left to right (the Earth's own turning), 1: the other way
    const val SKY_MOTION = "sky_motion"           // 0 slow drift (default): the stars hang on the picture and creep; 1 real sky: they stand in space and follow the orbit
    const val FIXED_VIEW = "fixed_view"           // true: the camera hovers over one longitude instead of circling the Earth
    const val LONGITUDE = "longitude"             // degrees, -180..180 (east positive): where the fixed view looks
    const val STAR_DENSITY = "star_density"       // percent, 0..100: a stable share of the catalog stars
    const val STAR_SIZE = "star_size"             // percent, 50..200
    const val AURORA_STRENGTH = "aurora_strength" // percent, 0..200
    const val AIRGLOW_STRENGTH = "airglow_strength" // percent, 0..200
    const val EFFECT_QUALITY = "effect_quality"   // 0 high, 1 medium, 2 low: fewer shells, samples and faint stars, nothing switched off
    const val MAP_QUALITY = "map_quality"         // 0 standard, 1 low: the maps are loaded one mip level smaller (a quarter of the memory)
    const val LIGHTNING_RATE = "lightning_rate"   // percent, 25..500: how often the lightning flashes; 100 = as designed
    const val LAST_ERROR = "last_error"

    const val DEFAULT_SIZE = 92
    const val DEFAULT_ORBIT = 24
    const val DEFAULT_LATITUDE = 21.4f
    const val DEFAULT_STARS = 100
    const val DEFAULT_FPS = 10
    const val DEFAULT_RENDER_SCALE = 100
    const val DEFAULT_CLOUD_SPEED = 100
    const val DEFAULT_LIGHTNING_RATE = 100
    val FPS_CHOICES = intArrayOf(5, 10, 20, 30)
    const val CLOUD_LAP_SECONDS = 3600.0 // at 100 %

    /** Feature switch key -> the SKIP_* bit of earth.frag that is set while the feature is off. */
    val FEATURES: Map<String, Int> = linkedMapOf(
        "aurora" to 16, "lightning" to 2048, "airglow" to 1024, "cirrus" to 4096, "clouds" to 256, "city_lights" to 512,
        "sharp_clouds" to 64, "sun_moon_blur" to 8192, "sun" to 32, "moon" to 16384, "skyglow" to 2,
    )

    class Values(
        val fill: Double,
        val periodSeconds: Double,
        val latitudeDeg: Double,
        val starGain: Float,
        val quality: Int, // effects quality: 0 high, 1 medium, 2 low
        val mapSkip: Int, // mip levels left out of the big maps: 0 standard, 1 low
        val flashRate: Float,
        val fps: Int,
        val renderScale: Double,
        val cloudRate: Double, // laps per CLOUD_LAP_SECONDS; negative: towards the west; 0: still
        val orbitDirection: Int, // 1 or -1
        val realSky: Boolean, // the stars stand in space like the Moon (else a slow drift)
        val fixedLongitudeDeg: Double?, // null: the camera circles the Earth
        val starDensity: Float, // 0..1
        val starSize: Float, // 0.5..2
        val auroraGain: Float, // 0..2
        val airglowGain: Float, // 0..2
        val skipMask: Int,
    )

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Where the user was in the app (a file of its own: a change here must not reach the wallpaper). The settings page notes that it is open and
     * which tab; the start page opens it again when the app is started afresh within [RESUME_MS] while it was left open (home button, an
     * app switch, the system closing it), and not after the user went back out of it.
     */
    object Ui {
        private const val STATE = "ui_state"
        const val RESUME_MS = 30 * 60 * 1000L
        private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)

        fun settingsOpened(context: Context, tab: Int) = state(context).edit().putBoolean("settings_open", true).putLong("settings_at", System.currentTimeMillis()).putInt("settings_tab", tab).apply()
        fun settingsTab(context: Context) = state(context).getInt("settings_tab", 0).coerceIn(0, 3)
        fun settingsClosed(context: Context) = state(context).edit().putBoolean("settings_open", false).apply()
        fun shouldResumeSettings(context: Context, now: Long = System.currentTimeMillis()): Boolean {
            val s = state(context)
            return s.getBoolean("settings_open", false) && now - s.getLong("settings_at", 0L) in 0..RESUME_MS
        }
    }

    fun skipMask(enabled: (String) -> Boolean): Int = FEATURES.entries.filter { !enabled(it.key) }.fold(0) { mask, e -> mask or e.value }

    fun read(prefs: SharedPreferences) = Values(
        fill = prefs.getInt(EARTH_SIZE, DEFAULT_SIZE).coerceIn(70, 100) / 100.0,
        periodSeconds = prefs.getInt(ORBIT_MINUTES, DEFAULT_ORBIT).coerceIn(1, 120) * 60.0,
        latitudeDeg = prefs.getFloat(LATITUDE, DEFAULT_LATITUDE).coerceIn(-60f, 60f).toDouble(),
        starGain = prefs.getInt(STAR_BRIGHTNESS, DEFAULT_STARS).coerceIn(25, 150) / 100f,
        flashRate = prefs.getInt(LIGHTNING_RATE, DEFAULT_LIGHTNING_RATE).coerceIn(25, 500) / 100f,
        quality = prefs.getInt(EFFECT_QUALITY, 0).coerceIn(0, 2),
        mapSkip = if (prefs.getInt(MAP_QUALITY, 0) == 1) 1 else 0,
        fps = prefs.getInt(FPS, DEFAULT_FPS).takeIf { it in FPS_CHOICES } ?: DEFAULT_FPS,
        renderScale = prefs.getInt(RENDER_SCALE, DEFAULT_RENDER_SCALE).coerceIn(50, 100) / 100.0,
        cloudRate = prefs.getInt(CLOUD_SPEED, DEFAULT_CLOUD_SPEED).coerceIn(0, 300) / 100.0 * (if (prefs.getInt(CLOUD_DIRECTION, 0) == 1) -1 else 1),
        orbitDirection = if (prefs.getInt(ORBIT_DIRECTION, 0) == 1) -1 else 1,
        realSky = prefs.getInt(SKY_MOTION, 0) == 1,
        fixedLongitudeDeg = if (prefs.getBoolean(FIXED_VIEW, false)) prefs.getInt(LONGITUDE, 0).coerceIn(-180, 180).toDouble() else null,
        starDensity = prefs.getInt(STAR_DENSITY, 100).coerceIn(0, 100) / 100f,
        starSize = prefs.getInt(STAR_SIZE, 100).coerceIn(50, 200) / 100f,
        auroraGain = prefs.getInt(AURORA_STRENGTH, 100).coerceIn(0, 200) / 100f,
        airglowGain = prefs.getInt(AIRGLOW_STRENGTH, 100).coerceIn(0, 200) / 100f,
        skipMask = skipMask { prefs.getBoolean(it, true) },
    )

    fun reset(prefs: SharedPreferences) {
        prefs.edit().apply {
            listOf(EARTH_SIZE, ORBIT_MINUTES, ORBIT_DIRECTION, SKY_MOTION, FIXED_VIEW, LONGITUDE, STAR_DENSITY, STAR_SIZE, AURORA_STRENGTH, AIRGLOW_STRENGTH, LATITUDE, STAR_BRIGHTNESS, LIGHTNING_RATE, EFFECT_QUALITY, MAP_QUALITY, FPS, RENDER_SCALE, CLOUD_SPEED, CLOUD_DIRECTION, "cloud_speed").plus(FEATURES.keys).forEach(::remove)
        }.apply()
    }

    fun setError(context: Context, text: String?) {
        get(context).edit().apply { if (text == null) remove(LAST_ERROR) else putString(LAST_ERROR, text) }.apply()
    }
}
