package com.example.earthlive

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
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
        picture.addView(slider(R.string.earth_size, R.string.earth_size_hint, 70, 100, prefs.getInt(Prefs.EARTH_SIZE, Prefs.DEFAULT_SIZE).coerceIn(70, 100), { "$it %" }) { prefs.edit().putInt(Prefs.EARTH_SIZE, it).apply() })
        picture.addView(Ui.divider(this))
        picture.addView(slider(R.string.orbit, R.string.orbit_hint, 6, 60, prefs.getInt(Prefs.ORBIT_MINUTES, Prefs.DEFAULT_ORBIT).coerceIn(6, 60), { getString(R.string.minutes, it) }) { prefs.edit().putInt(Prefs.ORBIT_MINUTES, it).apply() })
        picture.addView(Ui.divider(this))
        picture.addView(slider(R.string.latitude, R.string.latitude_hint, -60, 60, prefs.getFloat(Prefs.LATITUDE, Prefs.DEFAULT_LATITUDE).roundToInt().coerceIn(-60, 60), { latitudeText(it) }) { prefs.edit().putFloat(Prefs.LATITUDE, it.toFloat()).apply() })
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
        )
        rows.forEachIndexed { i, (key, title, hint) ->
            if (i > 0) details.addView(Ui.divider(this))
            details.addView(toggle(key, title, hint))
        }
        column.addView(details)

        column.addView(Ui.sectionLabel(this, getString(R.string.section_performance)))
        val performance = Ui.card(this)
        performance.addView(Ui.text(this, getString(R.string.fps), 16f, R.color.text, bold = true))
        performance.addView(Ui.body(this, getString(R.string.fps_hint)), Ui.lp(this, 4))
        performance.addView(fpsChoice(), Ui.lp(this, 14))
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

    private fun slider(title: Int, hint: Int, min: Int, max: Int, value: Int, format: (Int) -> String, save: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this)
        head.addView(Ui.text(this, getString(title), 16f, R.color.text, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = Ui.text(this, format(value), 16f, R.color.accent, bold = true)
        head.addView(valueText)
        box.addView(head)
        box.addView(Ui.body(this, getString(hint)), Ui.lp(this, 4))
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    valueText.text = format(progress + min)
                    if (fromUser) save(progress + min)
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

    private fun fpsChoice(): View {
        val row = LinearLayout(this)
        val buttons = ArrayList<TextView>()
        fun paint() {
            val selected = Prefs.read(prefs).fps
            buttons.forEachIndexed { i, b ->
                val on = Prefs.FPS_CHOICES[i] == selected
                b.background = Ui.rounded(this, getColor(if (on) R.color.accent else R.color.card), 14, getColor(R.color.card_stroke))
                b.setTextColor(getColor(if (on) R.color.on_accent else R.color.text))
            }
        }
        Prefs.FPS_CHOICES.forEach { fps ->
            val b = Ui.text(this, "$fps fps", 15f, R.color.text, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = Ui.dp(this@SettingsActivity, 48)
                setOnClickListener {
                    prefs.edit().putInt(Prefs.FPS, fps).apply()
                    paint()
                }
            }
            buttons += b
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (fps != Prefs.FPS_CHOICES.last()) marginEnd = Ui.dp(this@SettingsActivity, 10) })
        }
        paint()
        return row
    }
}
