package com.marco.mlpod.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PresetTextTest {
    @Test
    fun parsesAutoEqText() {
        val t = "Preamp: -6.4 dB\nFilter 1: ON LS Fc 105 Hz Gain 6.5 dB Q 0.70\nFilter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41\nFilter 3: OFF PK Fc 3000 Hz Gain 3.0 dB Q 2\nFilter 4: ON HS Fc 8000 Hz Gain 12 dB Q 0.7\n"
        val c = PresetText.parse(t, EqConfig())!!
        assertEquals(-6.4f, c.preampDb, 1e-4f)
        assertEquals(65, c.bassPercent)
        assertEquals(100, c.treblePercent)
        assertEquals(3, c.bandCount)
        assertEquals(1000f, c.freqs[0], 1e-3f)
        assertEquals(-2f, c.gains[0], 1e-4f)
    }

    @Test
    fun exportRoundTrip() {
        val c = EqConfig(enabled = true).withGain(2, 4f).withGain(5, -3f).copy(preampDb = -2f, bassPercent = 30)
        val r = PresetText.parse(PresetText.export(c), EqConfig())!!
        assertEquals(30, r.bassPercent)
        assertEquals(-2f, r.preampDb, 1e-4f)
        assertEquals(2, r.freqs.indices.count { r.gains[it] != 0f })
    }

    @Test
    fun rejectsGarbage() = assertNull(PresetText.parse("ciao", EqConfig()))

    @Test
    fun graphicEq() = assertNotNull(PresetText.parse("GraphicEQ: 20 0; 1000 3; 20000 -2", EqConfig()))
}
