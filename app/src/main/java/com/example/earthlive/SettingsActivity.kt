package com.example.earthlive

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import kotlin.math.roundToInt

/**
 * Settings in four tabs (View, Space, Earth, Quality) as in the Wallpaper Engine version. A setting that does not apply is hidden:
 * the orbit settings while the view is fixed, the star sliders without stars, everything of the clouds, the aurora and the airglow
 * while that part is off. Every change is written at once and a running wallpaper picks it up at once (same process, see EarthWallpaperService).
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var column: LinearLayout
    private var tab = 0
    private val conditional = ArrayList<Pair<View, () -> Boolean>>()
    // the framework keeps preference listeners weakly: a field keeps this one alive
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyVisibility() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        tab = savedInstanceState?.getInt(KEY_TAB) ?: Prefs.Ui.settingsTab(this) // the tab the user was on when the page was left open
        val (scroll, col) = Ui.page(this)
        column = col
        setContentView(scroll)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        Prefs.Ui.settingsOpened(this, tab)
    }

    override fun onPause() {
        super.onPause()
        // the user went back out of the page (not home, not an app switch, not the system closing it): the start page shows next time
        if (isFinishing) Prefs.Ui.settingsClosed(this) else Prefs.Ui.settingsOpened(this, tab)
    }

    override fun onDestroy() {
        // swiped away in the recent apps (the activity is finished then, unlike when the system only frees its memory): start on the start page again
        if (isFinishing) Prefs.Ui.settingsClosed(this)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    private fun flag(key: String, default: Boolean = true) = prefs.getBoolean(key, default)
    private fun applyVisibility() = conditional.forEach { (view, shown) -> view.visibility = if (shown()) View.VISIBLE else View.GONE }

    private fun build() {
        column.removeAllViews()
        conditional.clear()
        column.addView(Ui.title(this, getString(R.string.settings)))
        column.addView(Ui.body(this, getString(R.string.settings_hint)), Ui.lp(this, 8))
        column.addView(
            choice(listOf(R.string.tab_view, R.string.tab_space, R.string.tab_earth, R.string.tab_quality).map(::getString), { tab }) {
                tab = it
                Prefs.Ui.settingsOpened(this, tab)
                build()
            },
            Ui.lp(this, 18),
        )
        val card = Ui.card(this)
        when (tab) {
            0 -> viewTab(card)
            1 -> spaceTab(card)
            2 -> earthTab(card)
            else -> qualityTab(card)
        }
        column.addView(card, Ui.lp(this, 16))
        column.addView(Ui.button(this, getString(R.string.reset), primary = false) {
            Prefs.reset(prefs)
            build()
        }, Ui.lp(this, 26))
        applyVisibility()
    }

    /** Adds [view] to [card] under a divider (not for the first one), shown only while [shown] holds. */
    private fun add(card: LinearLayout, view: View, first: Boolean = false, shown: (() -> Boolean)? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!first) box.addView(Ui.divider(this))
        box.addView(view)
        card.addView(box)
        if (shown != null) conditional += box to shown
    }

    private fun viewTab(card: LinearLayout) {
        add(card, slider(R.string.earth_size, R.string.earth_size_hint, 70, 100, 1, prefs.getInt(Prefs.EARTH_SIZE, Prefs.DEFAULT_SIZE), { "$it %" }) { prefs.edit().putInt(Prefs.EARTH_SIZE, it).apply() }, first = true)
        add(card, toggleRow(Prefs.FIXED_VIEW, R.string.fixed_view, R.string.fixed_view_hint, default = false))
        add(card, slider(R.string.longitude, R.string.longitude_hint, -180, 180, 1, prefs.getInt(Prefs.LONGITUDE, 0), { longitudeText(it) }) { prefs.edit().putInt(Prefs.LONGITUDE, it).apply() }) { flag(Prefs.FIXED_VIEW, false) }
        add(card, slider(R.string.orbit, R.string.orbit_hint, 1, 120, 1, prefs.getInt(Prefs.ORBIT_MINUTES, Prefs.DEFAULT_ORBIT), { getString(R.string.minutes, it) }) { prefs.edit().putInt(Prefs.ORBIT_MINUTES, it).apply() }) { !flag(Prefs.FIXED_VIEW, false) }
        add(card, choiceRow(R.string.orbit_direction, R.string.orbit_direction_hint, listOf(R.string.orbit_normal, R.string.orbit_reverse), Prefs.ORBIT_DIRECTION)) { !flag(Prefs.FIXED_VIEW, false) }
        add(card, slider(R.string.latitude, R.string.latitude_hint, -60, 60, 1, prefs.getFloat(Prefs.LATITUDE, Prefs.DEFAULT_LATITUDE).roundToInt(), { latitudeText(it) }) { prefs.edit().putFloat(Prefs.LATITUDE, it.toFloat()).apply() })
    }

    private fun spaceTab(card: LinearLayout) {
        val stars = { prefs.getInt(Prefs.STAR_DENSITY, 100) > 0 }
        add(card, slider(R.string.star_density, R.string.star_density_hint, 0, 100, 5, prefs.getInt(Prefs.STAR_DENSITY, 100), { "$it %" }) { prefs.edit().putInt(Prefs.STAR_DENSITY, it).apply() }, first = true)
        add(card, slider(R.string.star_size, R.string.star_size_hint, 50, 200, 5, prefs.getInt(Prefs.STAR_SIZE, 100), { "$it %" }) { prefs.edit().putInt(Prefs.STAR_SIZE, it).apply() }, shown = stars)
        add(card, slider(R.string.stars, R.string.stars_hint, 25, 150, 5, prefs.getInt(Prefs.STAR_BRIGHTNESS, Prefs.DEFAULT_STARS), { "$it %" }) { prefs.edit().putInt(Prefs.STAR_BRIGHTNESS, it).apply() }, shown = stars)
        add(card, choiceRow(R.string.sky_motion, R.string.sky_motion_hint, listOf(R.string.sky_drift, R.string.sky_real), Prefs.SKY_MOTION)) { stars() || flag("skyglow") }
        add(card, toggleRow("skyglow", R.string.skyglow, R.string.skyglow_hint))
        add(card, toggleRow("sun", R.string.sun, R.string.sun_hint))
        add(card, toggleRow("moon", R.string.moon, R.string.moon_hint))
        add(card, toggleRow("sun_moon_blur", R.string.sun_blur, R.string.sun_blur_hint)) { !flag(Prefs.FIXED_VIEW, false) && (flag("sun") || flag("moon")) }
    }

    private fun earthTab(card: LinearLayout) {
        val clouds = { flag("clouds") }
        add(card, toggleRow("clouds", R.string.clouds, R.string.clouds_hint), first = true)
        add(card, toggleRow("cirrus", R.string.cirrus, R.string.cirrus_hint), shown = clouds)
        add(card, slider(R.string.cloud_speed, R.string.cloud_speed_hint, 0, 300, 10, prefs.getInt(Prefs.CLOUD_SPEED, Prefs.DEFAULT_CLOUD_SPEED), { "$it %" }) { prefs.edit().putInt(Prefs.CLOUD_SPEED, it).apply() }, shown = clouds)
        add(card, choiceRow(R.string.cloud_direction, R.string.cloud_direction_hint, listOf(R.string.cloud_east, R.string.cloud_west), Prefs.CLOUD_DIRECTION)) { clouds() && prefs.getInt(Prefs.CLOUD_SPEED, Prefs.DEFAULT_CLOUD_SPEED) > 0 }
        add(card, toggleRow("city_lights", R.string.city_lights, R.string.city_lights_hint))
        add(card, toggleRow("aurora", R.string.aurora, R.string.aurora_hint))
        add(card, slider(R.string.aurora_strength, R.string.aurora_strength_hint, 0, 200, 5, prefs.getInt(Prefs.AURORA_STRENGTH, 100), { "$it %" }) { prefs.edit().putInt(Prefs.AURORA_STRENGTH, it).apply() }) { flag("aurora") }
        add(card, toggleRow("airglow", R.string.airglow, R.string.airglow_hint))
        add(card, slider(R.string.airglow_strength, R.string.airglow_strength_hint, 0, 200, 5, prefs.getInt(Prefs.AIRGLOW_STRENGTH, 100), { "$it %" }) { prefs.edit().putInt(Prefs.AIRGLOW_STRENGTH, it).apply() }) { flag("airglow") }
        add(card, toggleRow("lightning", R.string.lightning, R.string.lightning_hint), shown = clouds)
        add(card, slider(R.string.lightning_rate, R.string.lightning_rate_hint, 25, 500, 25, prefs.getInt(Prefs.LIGHTNING_RATE, Prefs.DEFAULT_LIGHTNING_RATE), { "$it %" }) { prefs.edit().putInt(Prefs.LIGHTNING_RATE, it).apply() }) { clouds() && flag("lightning") }
    }

    private fun qualityTab(card: LinearLayout) {
        add(card, choiceRow(R.string.fps, R.string.fps_hint, Prefs.FPS_CHOICES.map { "$it fps" }, selected = { Prefs.FPS_CHOICES.indexOf(Prefs.read(prefs).fps) }) { prefs.edit().putInt(Prefs.FPS, Prefs.FPS_CHOICES[it]).apply() }, first = true)
        add(card, slider(R.string.render_scale, R.string.render_scale_hint, 50, 100, 5, prefs.getInt(Prefs.RENDER_SCALE, Prefs.DEFAULT_RENDER_SCALE), { "$it %" }) { prefs.edit().putInt(Prefs.RENDER_SCALE, it).apply() })
        add(card, choiceRow(R.string.effect_quality, R.string.effect_quality_hint, listOf(R.string.quality_high, R.string.quality_medium, R.string.quality_low), Prefs.EFFECT_QUALITY))
        add(card, choiceRow(R.string.map_quality, R.string.map_quality_hint, listOf(R.string.map_standard, R.string.map_low), Prefs.MAP_QUALITY))
        add(card, toggleRow("sharp_clouds", R.string.sharp_clouds, R.string.sharp_clouds_hint)) { flag("clouds") }
    }

    private fun longitudeText(deg: Int) = when {
        deg > 0 -> getString(R.string.east, deg)
        deg < 0 -> getString(R.string.west, -deg)
        else -> getString(R.string.lon_zero)
    }

    private fun latitudeText(deg: Int) = when {
        deg > 0 -> getString(R.string.north, deg)
        deg < 0 -> getString(R.string.south, -deg)
        else -> getString(R.string.equator)
    }

    /** A title, a hint and a slider from [min] to [max] in steps of [step]; the saved value is snapped to the nearest step. */
    private fun slider(title: Int, hint: Int, min: Int, max: Int, step: Int, value: Int, format: (Int) -> String, save: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this)
        head.addView(Ui.text(this, getString(title), 16f, R.color.text, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val shown = value.coerceIn(min, max)
        val valueText = Ui.text(this, format(shown), 16f, R.color.accent, bold = true)
        head.addView(valueText)
        box.addView(head)
        box.addView(Ui.body(this, getString(hint)), Ui.lp(this, 4))
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = ((shown - min).toFloat() / step).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    valueText.text = format(min + progress * step)
                    if (fromUser) save(min + progress * step)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        box.addView(bar, Ui.lp(this, 10))
        return box
    }

    /** A switch stored under [key] ("on" is the default except where [default] says otherwise). */
    private fun toggleRow(key: String, title: Int, hint: Int, default: Boolean = true): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, getString(title), 16f, R.color.text, bold = true))
        texts.addView(Ui.body(this, getString(hint)), Ui.lp(this, 3))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@SettingsActivity, 14) })
        row.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        })
        return row
    }

    /** A title, a hint and a row of buttons for an int preference (0, 1, 2 ...). */
    private fun choiceRow(title: Int, hint: Int, labels: List<Int>, key: String): View =
        choiceRow(title, hint, labels.map(::getString), selected = { prefs.getInt(key, 0) }) { prefs.edit().putInt(key, it).apply() }

    private fun choiceRow(title: Int, hint: Int, labels: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.text(this, getString(title), 16f, R.color.text, bold = true))
        box.addView(Ui.body(this, getString(hint)), Ui.lp(this, 4))
        box.addView(choice(labels, selected, onSelect), Ui.lp(this, 14))
        return box
    }

    /** A row of equal buttons, one of them selected. */
    private fun choice(labels: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(this)
        val buttons = ArrayList<android.widget.TextView>()
        fun paint() {
            buttons.forEachIndexed { i, b ->
                val on = i == selected()
                b.background = Ui.rounded(this, getColor(if (on) R.color.accent else R.color.card), 14, getColor(R.color.card_stroke))
                b.setTextColor(getColor(if (on) R.color.on_accent else R.color.text))
            }
        }
        labels.forEachIndexed { i, label ->
            val b = Ui.text(this, label, 14f, R.color.text, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = Ui.dp(this@SettingsActivity, 48)
                setOnClickListener {
                    onSelect(i)
                    paint()
                }
            }
            buttons += b
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < labels.lastIndex) marginEnd = Ui.dp(this@SettingsActivity, 8) })
        }
        paint()
        return row
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
