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

/** Settings; every change is written at once and a running wallpaper picks it up at once (same process, see EarthWallpaperService). */
class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        val (scroll, col) = Ui.page(this)
        column = col
        setContentView(scroll)
        build()
    }

    private fun build() {
        column.removeAllViews()
        column.addView(Ui.title(this, getString(R.string.settings)))
        column.addView(Ui.body(this, getString(R.string.settings_hint)), Ui.lp(this, 8))

        column.addView(Ui.sectionLabel(this, getString(R.string.section_picture)))
        val picture = Ui.card(this)
        picture.addView(slider(R.string.earth_size, R.string.earth_size_hint, 70, 100, 1, prefs.getInt(Prefs.EARTH_SIZE, Prefs.DEFAULT_SIZE), { "$it %" }) { prefs.edit().putInt(Prefs.EARTH_SIZE, it).apply() })
        picture.addView(Ui.divider(this))
        picture.addView(slider(R.string.orbit, R.string.orbit_hint, 1, 120, 1, prefs.getInt(Prefs.ORBIT_MINUTES, Prefs.DEFAULT_ORBIT), { getString(R.string.minutes, it) }) { prefs.edit().putInt(Prefs.ORBIT_MINUTES, it).apply() })
        picture.addView(Ui.divider(this))
        picture.addView(Ui.text(this, getString(R.string.orbit_direction), 16f, R.color.text, bold = true))
        picture.addView(Ui.body(this, getString(R.string.orbit_direction_hint)), Ui.lp(this, 4))
        picture.addView(
            choice(listOf(R.string.orbit_normal, R.string.orbit_reverse).map(::getString), { prefs.getInt(Prefs.ORBIT_DIRECTION, 0) }) { prefs.edit().putInt(Prefs.ORBIT_DIRECTION, it).apply() },
            Ui.lp(this, 14),
        )
        picture.addView(Ui.divider(this))
        picture.addView(slider(R.string.latitude, R.string.latitude_hint, -60, 60, 1, prefs.getFloat(Prefs.LATITUDE, Prefs.DEFAULT_LATITUDE).roundToInt(), { latitudeText(it) }) { prefs.edit().putFloat(Prefs.LATITUDE, it.toFloat()).apply() })
        picture.addView(Ui.divider(this))
        picture.addView(slider(R.string.stars, R.string.stars_hint, 25, 150, 5, prefs.getInt(Prefs.STAR_BRIGHTNESS, Prefs.DEFAULT_STARS), { "$it %" }) { prefs.edit().putInt(Prefs.STAR_BRIGHTNESS, it).apply() })
        picture.addView(Ui.divider(this))
        picture.addView(Ui.text(this, getString(R.string.sky_motion), 16f, R.color.text, bold = true))
        picture.addView(Ui.body(this, getString(R.string.sky_motion_hint)), Ui.lp(this, 4))
        picture.addView(
            choice(listOf(R.string.sky_drift, R.string.sky_real).map(::getString), { prefs.getInt(Prefs.SKY_MOTION, 0) }) { prefs.edit().putInt(Prefs.SKY_MOTION, it).apply() },
            Ui.lp(this, 14),
        )
        column.addView(picture)

        column.addView(Ui.sectionLabel(this, getString(R.string.section_details)))
        val details = Ui.card(this)
        val rows = listOf(
            Triple("clouds", R.string.clouds, R.string.clouds_hint),
            Triple("cirrus", R.string.cirrus, R.string.cirrus_hint),
            Triple("city_lights", R.string.city_lights, R.string.city_lights_hint),
            Triple("aurora", R.string.aurora, R.string.aurora_hint),
            Triple("airglow", R.string.airglow, R.string.airglow_hint),
            Triple("lightning", R.string.lightning, R.string.lightning_hint),
            Triple("sun", R.string.sun, R.string.sun_hint),
            Triple("moon", R.string.moon, R.string.moon_hint),
        )
        rows.forEach { (key, title, hint) ->
            details.addView(toggle(key, title, hint))
            details.addView(Ui.divider(this))
        }
        details.addView(slider(R.string.lightning_rate, R.string.lightning_rate_hint, 25, 500, 25, prefs.getInt(Prefs.LIGHTNING_RATE, Prefs.DEFAULT_LIGHTNING_RATE), { "$it %" }) { prefs.edit().putInt(Prefs.LIGHTNING_RATE, it).apply() })
        details.addView(Ui.divider(this))
        details.addView(slider(R.string.cloud_speed, R.string.cloud_speed_hint, 0, 300, 10, prefs.getInt(Prefs.CLOUD_SPEED, Prefs.DEFAULT_CLOUD_SPEED), { "$it %" }) { prefs.edit().putInt(Prefs.CLOUD_SPEED, it).apply() })
        details.addView(Ui.divider(this))
        details.addView(Ui.text(this, getString(R.string.cloud_direction), 16f, R.color.text, bold = true))
        details.addView(Ui.body(this, getString(R.string.cloud_direction_hint)), Ui.lp(this, 4))
        details.addView(
            choice(listOf(R.string.cloud_east, R.string.cloud_west).map(::getString), { prefs.getInt(Prefs.CLOUD_DIRECTION, 0) }) { prefs.edit().putInt(Prefs.CLOUD_DIRECTION, it).apply() },
            Ui.lp(this, 14),
        )
        column.addView(details)

        column.addView(Ui.sectionLabel(this, getString(R.string.section_performance)))
        val performance = Ui.card(this)
        performance.addView(Ui.text(this, getString(R.string.fps), 16f, R.color.text, bold = true))
        performance.addView(Ui.body(this, getString(R.string.fps_hint)), Ui.lp(this, 4))
        performance.addView(
            choice(Prefs.FPS_CHOICES.map { "$it fps" }, { Prefs.FPS_CHOICES.indexOf(Prefs.read(prefs).fps) }) { prefs.edit().putInt(Prefs.FPS, Prefs.FPS_CHOICES[it]).apply() },
            Ui.lp(this, 14),
        )
        performance.addView(Ui.divider(this))
        performance.addView(slider(R.string.render_scale, R.string.render_scale_hint, 50, 100, 5, prefs.getInt(Prefs.RENDER_SCALE, Prefs.DEFAULT_RENDER_SCALE), { "$it %" }) { prefs.edit().putInt(Prefs.RENDER_SCALE, it).apply() })
        performance.addView(Ui.divider(this))
        performance.addView(Ui.text(this, getString(R.string.effect_quality), 16f, R.color.text, bold = true))
        performance.addView(Ui.body(this, getString(R.string.effect_quality_hint)), Ui.lp(this, 4))
        performance.addView(
            choice(listOf(R.string.quality_high, R.string.quality_medium, R.string.quality_low).map(::getString), { Prefs.read(prefs).quality }) { prefs.edit().putInt(Prefs.EFFECT_QUALITY, it).apply() },
            Ui.lp(this, 14),
        )
        performance.addView(Ui.divider(this))
        performance.addView(Ui.text(this, getString(R.string.map_quality), 16f, R.color.text, bold = true))
        performance.addView(Ui.body(this, getString(R.string.map_quality_hint)), Ui.lp(this, 4))
        performance.addView(
            choice(listOf(R.string.map_standard, R.string.map_low).map(::getString), { Prefs.read(prefs).mapSkip }) { prefs.edit().putInt(Prefs.MAP_QUALITY, it).apply() },
            Ui.lp(this, 14),
        )
        performance.addView(Ui.divider(this))
        performance.addView(toggle("sharp_clouds", R.string.sharp_clouds, R.string.sharp_clouds_hint))
        performance.addView(Ui.divider(this))
        performance.addView(toggle("sun_moon_blur", R.string.sun_blur, R.string.sun_blur_hint))
        column.addView(performance)

        column.addView(Ui.button(this, getString(R.string.reset), primary = false) {
            Prefs.reset(prefs)
            build()
        }, Ui.lp(this, 26))
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

    private fun toggle(key: String, title: Int, hint: Int): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, getString(title), 16f, R.color.text, bold = true))
        texts.addView(Ui.body(this, getString(hint)), Ui.lp(this, 3))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(this@SettingsActivity, 14) })
        row.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        })
        return row
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
}
