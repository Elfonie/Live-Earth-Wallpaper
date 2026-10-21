package com.example.earthlive

import java.time.Instant
import java.time.ZoneOffset

/** Picks the Blue Marble month that matches the date: snow line, sea ice and vegetation follow the calendar. */
object Seasons {
    fun dayAsset(epochMs: Long): String {
        val month = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).monthValue
        return "earth_day_%02d.ktx".format(month)
    }
}
