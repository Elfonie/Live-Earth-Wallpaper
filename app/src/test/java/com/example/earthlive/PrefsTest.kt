package com.example.earthlive

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsTest {
    @Test
    fun everythingOnSetsNoBit() = assertEquals(0, Prefs.skipMask { true })

    @Test
    fun theResumeWindowIsHalfAnHour() = assertEquals(30 * 60 * 1000L, Prefs.Ui.RESUME_MS)

    @Test
    fun eachOffSwitchSetsItsOwnBit() {
        assertEquals(2, Prefs.skipMask { it != "skyglow" })
        assertEquals(16, Prefs.skipMask { it != "aurora" })
        assertEquals(2048, Prefs.skipMask { it != "lightning" })
        assertEquals(1024 or 4096, Prefs.skipMask { it != "airglow" && it != "cirrus" })
    }

    @Test
    fun everythingOffSetsAllBits() = assertEquals(2 or 16 or 2048 or 1024 or 4096 or 256 or 512 or 64 or 8192 or 32 or 16384, Prefs.skipMask { false })

    @Test
    fun sunAndMoonHaveTheirOwnBits() {
        assertEquals(32, Prefs.skipMask { it != "sun" })
        assertEquals(16384, Prefs.skipMask { it != "moon" })
    }
}
