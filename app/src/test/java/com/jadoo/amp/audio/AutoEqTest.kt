package com.jadoo.amp.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqTest {

    private val index = """
        # Index
        This is a list of all equalization profiles.

        - [1Custom SA02](./crinacle/711%20in-ear/1Custom%20SA02) by crinacle on 711
        - [Sennheiser HD 650](./Rtings/over-ear/Sennheiser%20HD%20650) by Rtings
        - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
        - [Sennheiser HD 650 (sample 2)](./crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20650%20(sample%202)) by crinacle on GRAS 43AG-7
        - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
    """.trimIndent()

    @Test
    fun parsesIndexEntries() {
        val entries = AutoEq.parseIndex(index)
        assertEquals(5, entries.size)
        val first = entries[0]
        assertEquals("1Custom SA02", first.name)
        assertEquals("crinacle", first.source)
        assertEquals("711", first.rig)
        assertEquals("crinacle/711%20in-ear/1Custom%20SA02", first.path)
        assertNull(entries[1].rig)
        assertEquals("GRAS 43AG-7", entries[3].rig)
    }

    @Test
    fun searchRanksExactNameThenPreferredSource() {
        val results = AutoEq.search(AutoEq.parseIndex(index), "hd 650")
        assertEquals(listOf("oratory1990", "Rtings", "crinacle"), results.map { it.source })
        assertEquals("Sennheiser HD 650 (sample 2)", results.last().name)
        assertTrue(AutoEq.search(AutoEq.parseIndex(index), "   ").isEmpty())
    }

    @Test
    fun parsesParametricEq() {
        val profile = AutoEq.parseParametricEq("""
            Preamp: -6.1 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70
            Filter 2: ON PK Fc 8800 Hz Gain 5.1 dB Q 1.42
            Filter 3: OFF PK Fc 118 Hz Gain -3.1 dB Q 0.50
            Filter 6: ON HSC Fc 10000 Hz Gain -2.1 dB Q 0.70
        """.trimIndent())!!
        assertEquals(-6.1f, profile.preampDb, 0.001f)
        assertEquals(3, profile.bands.size)
        val shelf = profile.bands[0]
        assertEquals(DigitalFilterEngine.FilterType.LowShelf, shelf.type)
        assertEquals(105f, shelf.frequencyHz, 0.001f)
        assertEquals(6.4f, shelf.gainDb, 0.001f)
        assertEquals(0.7f, shelf.q, 0.001f)
        assertTrue(shelf.enabled)
        assertEquals(DigitalFilterEngine.FilterType.HighShelf, profile.bands[2].type)
    }

    @Test
    fun clampsOutOfRangeValues() {
        val profile = AutoEq.parseParametricEq("Filter 1: ON PK Fc 30000 Hz Gain 22.0 dB Q 40")!!
        val band = profile.bands.single()
        assertEquals(DigitalFilterEngine.MAX_FREQUENCY_HZ, band.frequencyHz, 0.001f)
        assertEquals(DigitalFilterEngine.MAX_GAIN_DB, band.gainDb, 0.001f)
        assertEquals(DigitalFilterEngine.MAX_Q, band.q, 0.001f)
        assertEquals(0f, profile.preampDb, 0.001f)
    }

    @Test
    fun rejectsTextWithoutFilters() {
        assertNull(AutoEq.parseParametricEq("Preamp: -3.0 dB"))
        assertNull(AutoEq.parseParametricEq("<html>404</html>"))
    }
}
