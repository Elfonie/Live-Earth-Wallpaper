package com.example.earthlive

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonsTest {
    private fun asset(iso: String) = Seasons.dayAsset(Instant.parse(iso).toEpochMilli())

    @Test
    fun everyMonthUsesItsOwnTexture() {
        val days = listOf("01-01", "02-28", "03-01", "04-15", "05-31", "06-01", "07-20", "08-31", "09-01", "10-20", "11-30", "12-31")
        days.forEachIndexed { index, day ->
            assertEquals("earth_day_%02d.ktx".format(index + 1), asset("2026-${day}T12:00:00Z"))
        }
    }

    @Test
    fun monthBoundaryFollowsUtc() {
        assertEquals("earth_day_10.ktx", asset("2026-10-31T23:59:59Z"))
        assertEquals("earth_day_11.ktx", asset("2026-11-01T00:00:00Z"))
    }
}
