package com.example.earthlive

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The start page: what the app is, whether this device can run it, and the way to the wallpaper chooser, settings and credits. */
class MainActivity : Activity() {
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var statusDot: View
    private lateinit var setButton: Button
    private var check: GpuCheck.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // started afresh while the settings were left open a short time ago: go back to where the user was (back leads here)
        if (savedInstanceState == null && Prefs.Ui.shouldResumeSettings(this)) startActivity(Intent(this, SettingsActivity::class.java))
        val (scroll, column) = Ui.page(this)

        val hero = ImageView(this).apply {
            setImageResource(R.drawable.hero)
            adjustViewBounds = true // the picture keeps its 4:3 shape at the full width
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(this@MainActivity, 28).toFloat())
            }
            clipToOutline = true
            contentDescription = getString(R.string.hero_description)
        }
        column.addView(hero, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(Ui.title(this, getString(R.string.app_name)), Ui.lp(this, 22))
        column.addView(Ui.body(this, getString(R.string.tagline)), Ui.lp(this, 8))

        val status = Ui.card(this)
        val head = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        statusDot = View(this)
        head.addView(statusDot, LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)).apply { marginEnd = Ui.dp(this@MainActivity, 12) })
        statusTitle = Ui.text(this, getString(R.string.status_checking), 17f, R.color.text, bold = true)
        head.addView(statusTitle)
        status.addView(head)
        statusBody = Ui.body(this, "").apply { visibility = View.GONE }
        status.addView(statusBody, Ui.lp(this, 8))
        column.addView(status, Ui.lp(this, 22))

        setButton = Ui.button(this, getString(R.string.set_wallpaper), primary = true) { setWallpaper() }
        column.addView(setButton, Ui.lp(this, 18))
        column.addView(Ui.button(this, getString(R.string.settings), primary = false) { startActivity(Intent(this, SettingsActivity::class.java)) }, Ui.lp(this, 12))
        column.addView(Ui.button(this, getString(R.string.credits), primary = false) { startActivity(Intent(this, CreditsActivity::class.java)) }, Ui.lp(this, 12))

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        column.addView(
            Ui.text(this, getString(R.string.footer, version), 12f, R.color.text2).apply { gravity = android.view.Gravity.CENTER },
            Ui.lp(this, 26),
        )
        setContentView(scroll)

        Thread { check = GpuCheck.run(); runOnUiThread { refresh() } }.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun isActive() = WallpaperManager.getInstance(this).wallpaperInfo?.component == ComponentName(this, EarthWallpaperService::class.java)

    private fun refresh() {
        val result = check
        val lastError = Prefs.get(this).getString(Prefs.LAST_ERROR, null)
        val (dotColor, title, body) = when {
            result == null -> Triple(R.color.text2, getString(R.string.status_checking), null)
            !result.supported -> Triple(R.color.bad, getString(R.string.status_unsupported), result.problems.joinToString("\n") + "\n" + result.gpu)
            lastError != null -> Triple(R.color.warn, getString(R.string.status_error), getString(R.string.status_error_body, lastError))
            isActive() -> Triple(R.color.good, getString(R.string.status_active), getString(R.string.status_gpu, result.gpu))
            else -> Triple(R.color.good, getString(R.string.status_ready), getString(R.string.status_gpu, result.gpu))
        }
        statusDot.background = Ui.rounded(this, getColor(dotColor), 5)
        statusTitle.text = title
        statusBody.visibility = if (body == null) View.GONE else View.VISIBLE
        statusBody.text = body
        setButton.isEnabled = result?.supported != false
        setButton.alpha = if (setButton.isEnabled) 1f else 0.4f
    }

    private fun setWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, EarthWallpaperService::class.java))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) { // a few devices lack the direct preview: the general chooser always exists
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
