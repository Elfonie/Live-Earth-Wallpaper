package com.example.earthlive

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The few building blocks of the start, settings and credits pages (plain framework views, dark theme). */
object Ui {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    private fun color(context: Context, id: Int) = context.getColor(id)

    fun page(context: Context): Pair<ScrollView, LinearLayout> {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = dp(context, 20)
            setPadding(side, dp(context, 28), side, dp(context, 32))
        }
        val scroll = ScrollView(context).apply {
            setBackgroundColor(color(context, R.color.bg))
            isFillViewport = true
            clipToPadding = false
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return scroll to column
    }

    fun rounded(context: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
    }

    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(context, color(context, R.color.card), 20, color(context, R.color.card_stroke))
        val pad = dp(context, 18)
        setPadding(pad, pad, pad, pad)
    }

    fun text(context: Context, text: CharSequence, sizeSp: Float, colorId: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color(context, colorId))
        if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    fun title(context: Context, text: CharSequence) = text(context, text, 30f, R.color.text, bold = true)
    fun body(context: Context, text: CharSequence) = text(context, text, 15f, R.color.text2)

    fun sectionLabel(context: Context, text: CharSequence) = text(context, text.toString().uppercase(), 12f, R.color.accent, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(dp(context, 4), dp(context, 26), 0, dp(context, 8))
    }

    fun button(context: Context, text: CharSequence, primary: Boolean, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(color(context, if (primary) R.color.on_accent else R.color.text))
        typeface = Typeface.create(typeface, Typeface.BOLD)
        val shape = if (primary) rounded(context, color(context, R.color.accent), 16)
        else rounded(context, color(context, R.color.card), 16, color(context, R.color.card_stroke))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        minimumHeight = dp(context, 54)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    fun lp(context: Context, topDp: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, topDp) }

    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(color(context, R.color.card_stroke))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
            topMargin = dp(context, 14)
            bottomMargin = dp(context, 14)
        }
    }
}
