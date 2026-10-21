package com.example.earthlive

import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod

/** The credits and licenses of the data (see ATTRIBUTION.md in the repository for the full terms). */
class CreditsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, column) = Ui.page(this)
        column.addView(Ui.title(this, getString(R.string.credits)))
        val card = Ui.card(this)
        val text = Ui.body(this, Html.fromHtml(getString(R.string.credits_html), Html.FROM_HTML_MODE_COMPACT)).apply {
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(getColor(R.color.accent))
            setTextIsSelectable(true)
        }
        card.addView(text)
        column.addView(card, Ui.lp(this, 18))
        setContentView(scroll)
    }
}
