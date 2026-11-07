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
    @Test
    fun powerampParametricJsonKeepsFilters() {
        // tipi di Poweramp (ordine del menu): 0 passa basso, 1 passa alto, 2 passa banda, 3 campanatura, 4/5 scaffali
        val json = """[{"name":"Pro30","preamp":-2.0,"parametric":true,"bands":[
            {"type":0,"channels":0,"frequency":90,"q":0.8,"gain":0.0,"color":0},
            {"type":0,"channels":0,"frequency":105,"q":0.7,"gain":4.5,"color":0},
            {"type":2,"channels":0,"frequency":12500,"q":1.41,"gain":2.0,"color":0},
            {"type":3,"channels":0,"frequency":4181,"q":5.53,"gain":5.0,"color":0},
            {"type":5,"channels":0,"frequency":10000,"q":0.7,"gain":2.5,"color":0}]}]"""
        val l = PresetText.parseParametric(json, "x")
        assertEquals(1, l.size)
        val b = l[0].bands
        assertEquals(4, b.size)                                  // la manopola a 0 dB non conta
        assertEquals(PeqBand.LS, b[0].type)
        assertEquals(PeqBand.PK, b[1].type)
        assertEquals(PeqBand.PK, b[2].type)
        assertEquals(PeqBand.HS, b[3].type)
        assertEquals(-2f, l[0].preampDb, 0f)
        assertTrue(l[0].notes.isNotEmpty())                      // passa basso e passa banda approssimati
    }

    @Test
    fun parametricTextRoundTrip() {
        val txt = "Preamp: -6.5 dB\nFilter 1: ON PK Fc 54 Hz Gain -10.0 dB Q 0.65\nFilter 2: ON LS Fc 105 Hz Gain 4.9 dB Q 0.70\nFilter 3: ON HP Fc 25 Hz Q 0.71\n"
        val p = PresetText.parseParametric(txt, "x").single()
        assertEquals(3, p.bands.size)
        assertEquals(PeqBand.HP, p.bands[2].type)
        val s = DspSettings(eqMode = DspSettings.EQ_PARAMETRIC, peq = p.bands, peqPreamp = p.preampDb)
        val back = PresetText.parseParametric(PresetText.exportParametric(s), "y").single()
        assertEquals(p.bands, back.bands)
        assertEquals(-6.5f, back.preampDb, 0f)
        // JSON: campana 3, scaffale basso 4; il passa-alto non esiste con guadagno e resta fuori
        val js = PresetText.exportPoweramp("t", s)
        assertTrue(js.contains("\"type\": 3"))
        assertTrue(js.contains("\"type\": 4"))
        assertEquals(1, PresetText.powerampSkipped(s))
    }

    @Test
    fun parametricSettingsPersistAndReachEngine() {
        val s = DspSettings(master = true, eqMode = DspSettings.EQ_AUTOEQ, autoEqName = "Cuffia", peqPreamp = -3f,
            peq = listOf(PeqBand(true, PeqBand.PK, 1000f, 3f, 1.4f), PeqBand(false, PeqBand.LS, 100f, 4f, 0.7f)))
        val back = DspSettings.fromJson(s.toJson().toString())
        assertEquals(s.peq, back.peq)
        assertEquals(DspSettings.EQ_AUTOEQ, back.eqMode)
        assertEquals("Cuffia", back.autoEqName)
        val n = s.toNative()
        assertTrue(n.contains("peq=1;"))
        assertTrue(n.contains("eq=0;"))
        assertTrue(n.contains("peq.t=0,-1;"))
    }
}
