package com.example.earthlive

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsTest {
    @Test
    fun everythingOnSetsNoBit() = assertEquals(0, Prefs.skipMask { true })

    @Test
    fun eachOffSwitchSetsItsOwnBit() {
        assertEquals(16, Prefs.skipMask { it != "aurora" })
        assertEquals(2048, Prefs.skipMask { it != "lightning" })
        assertEquals(1024 or 4096, Prefs.skipMask { it != "airglow" && it != "cirrus" })
    }

    @Test
    fun everythingOffSetsAllBits() = assertEquals(16 or 2048 or 1024 or 4096 or 256 or 512, Prefs.skipMask { false })
}
