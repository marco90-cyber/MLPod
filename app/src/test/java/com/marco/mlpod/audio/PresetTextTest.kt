package com.marco.mlpod.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTextTest {
    @Test
    fun autoEqFiltersBecomeACurve() {
        val t = """
            Preamp: -6,4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70
            Filter 2: ON PK Fc 1000 Hz Gain -4.0 dB Q 1.41
            Filter 3: OFF PK Fc 3000 Hz Gain 9.0 dB Q 2.00
        """.trimIndent()
        val p = PresetText.parse(t)
        assertNotNull(p)
        p!!
        assertEquals(-6.4f, p.preampDb, 1e-4f)
        assertEquals(6.5f, p.gainAt(20f), 0.3f)          // sotto lo shelf
        assertEquals(-4f, p.gainAt(1000f), 0.3f)         // centro della campana
        assertEquals(0f, p.gainAt(3000f), 0.6f)          // filtro spento: ignorato
    }

    @Test
    fun graphicEqLineIsKeptAsIs() {
        val p = PresetText.parse("GraphicEQ: 20 2; 100 4; 1000 0; 10000 -3; 20000 -3")!!
        assertEquals(4f, p.gainAt(100f), 1e-4f)
        assertEquals(-3f, p.gainAt(10000f), 1e-4f)
        val s = DspSettings().withEqPart(p)                // sulle 10 bande predefinite
        assertEquals(0f, s.graphic[5], 1e-4f)             // 1 kHz
    }

    @Test
    fun powerampJsonIsRead() {
        val json = """[{"name":"Prova","preamp":-3,"bands":[{"type":2,"frequency":500,"q":1.5,"gain":3},{"type":0,"frequency":80,"q":0,"gain":4}]}]"""
        val l = PresetText.parseMulti(json, "x")
        assertEquals(1, l.size)
        assertEquals("Prova", l[0].first)
        assertEquals(-3f, l[0].second.preampDb, 0f)
        assertTrue(l[0].second.gainAt(500f) > 2.5f)
    }

    @Test
    fun exportAndImportAgree() {
        val s = DspSettings(preampOn = true, preampDb = -2f).withGain(5, 3f)   // 1 kHz +3 dB
        val text = PresetText.export(s)
        assertTrue(text.startsWith("Preamp: -2.0 dB"))
        val back = PresetText.parse(text)!!
        assertEquals(3f, back.gainAt(1000f), 0.6f)        // la curva delle campane (le vicine si sommano)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(PresetText.parse("ciao"))
        assertTrue(PresetText.parseMulti("[{\"x\":1}]", "x").isEmpty())
    }
}
