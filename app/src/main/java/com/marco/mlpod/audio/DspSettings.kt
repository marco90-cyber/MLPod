package com.marco.mlpod.audio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Etichette dei moduli a scelta multipla. */
object Modes {
    val surround = listOf("Spento", "Tradizionale", "Palco frontale", "Ultra Wide")
    val clarity = listOf("Riferimento", "Altoparlante", "Cuffie", "DAC")
}

/**
 * Tutte le impostazioni del motore DSP. Il motore nativo le riceve come testo "chiave=valore"
 * ([toNative]); qui stanno solo i valori, la matematica è nel motore.
 */
data class DspSettings(
    /** Spento = audio intatto (bit-exact). */
    val master: Boolean = false,

    // Equalizzatore grafico
    val eq: Boolean = true,
    val bands: Int = 10,
    val graphic: List<Float> = List(10) { 0f },
    val faithful: Boolean = true,

    /** Modo dell'equalizzatore: [EQ_GRAPHIC], [EQ_PARAMETRIC] o [EQ_AUTOEQ] (gli ultimi due usano [peq]). */
    val eqMode: Int = EQ_GRAPHIC,
    /** Filtri del parametrico (fino a [PEQ_MAX]). */
    val peq: List<PeqBand> = emptyList(),
    /** Preamp del preset parametrico (AutoEQ o file importato). */
    val peqPreamp: Float = 0f,
    /** Cuffia AutoEQ scelta (vuoto = nessuna). */
    val autoEqName: String = "",

    // Preamp
    val preampOn: Boolean = false,
    val preampDb: Float = 0f,

    // Toni (come Poweramp: shelf con frequenza e Q)
    val tone: Boolean = true,
    val bassDb: Float = 0f,
    val bassFreq: Float = 90f,
    val bassQ: Float = 0.8f,
    val trebleDb: Float = 0f,
    val trebleFreq: Float = 10000f,
    val trebleQ: Float = 0.6f,

    // Moduli
    val analogBass: Boolean = false,
    val analogDrive: Float = 0.4f,
    val analogWarmth: Float = 0.5f,
    val analogFreq: Float = 60f,
    val air: Boolean = false,
    val airAmount: Float = 0.5f,
    /** Scheda "Surround+ e stereo" accesa. */
    val stereo: Boolean = false,
    val surroundMode: Int = 0,
    val balance: Float = 0f,
    val expand: Float = 0f,
    val mono: Boolean = false,
    val crossfeed: Float = 0f,
    val dbfbMode: Int = 0,
    val loudness: Boolean = false,
    val loudnessAmount: Float = 0.7f,
    /** Clarity (da LastWave): curva "da studio", exciter e bassi mono. */
    val clarity: Boolean = false,
    val clarityPreset: Int = 0,
    val clarityAmount: Float = 1f,

    // Pulizia e protezione
    /** Filtro anti-DC a 10 Hz all'ingresso del DSP. */
    val dcBlock: Boolean = true,
    /** Saturazione morbida a ginocchio prima del limiter. */
    val softSat: Boolean = false,

    // Livello
    val autoHeadroom: Boolean = true,
    val limiter: Boolean = true,
    val limiterThresholdDb: Float = -0.5f,
    val limiterReleaseMs: Float = 80f,

    /** ReplayGain: 0 = spento, 1 = brano, 2 = album (vedi [ReplayGain]). */
    val replayGain: Int = ReplayGain.OFF,
) {
    /** Frequenze delle bande attuali. */
    val freqs: List<Float> get() = freqsFor(bands)

    /** Testo per il motore nativo. [rgDb] ReplayGain del brano, [volume] volume del telefono 0..1 lineare. */
    fun toNative(rgDb: Double = 0.0, volume: Double = 1.0): String {
        val sb = StringBuilder(1024)
        fun put(k: String, v: Any) { sb.append(k).append('=').append(v).append(';') }
        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        fun b(v: Boolean) = if (v) 1 else 0
        put("master", b(master))
        put("pre", f(if (preampOn) preampDb else 0f))
        put("rg", String.format(Locale.US, "%.3f", rgDb))
        put("headroom", b(autoHeadroom))
        val para = eq && eqMode != EQ_GRAPHIC
        put("eq", b(eq && !para)); put("geq.faithful", b(faithful)); put("geq.n", bands)
        put("geq.f", freqs.joinToString(",") { f(it) })
        put("geq.g", List(bands) { graphic.getOrElse(it) { 0f } }.joinToString(",") { f(it) })
        put("peq", b(para)); put("peq.pre", f(peqPreamp))
        val pb = peq.take(PEQ_MAX)
        put("peq.t", pb.joinToString(",") { if (it.on) "${it.type}" else "-1" })
        put("peq.f", pb.joinToString(",") { f(it.freq) })
        put("peq.g", pb.joinToString(",") { f(it.gain) })
        put("peq.q", pb.joinToString(",") { f(it.q) })
        put("bass", f(if (tone) bassDb else 0f)); put("bass.f", f(bassFreq)); put("bass.q", f(bassQ))
        put("treble", f(if (tone) trebleDb else 0f)); put("treble.f", f(trebleFreq)); put("treble.q", f(trebleQ))
        put("abass", b(analogBass)); put("abass.drive", f(analogDrive)); put("abass.warmth", f(analogWarmth)); put("abass.f", f(analogFreq))
        put("air", b(air)); put("air.amount", f(airAmount))
        put("surround", if (stereo) surroundMode else 0); put("stereo", b(stereo))
        put("balance", f(balance)); put("expand", f(expand)); put("mono", b(mono)); put("crossfeed", f(crossfeed))
        put("dbfb", dbfbMode)
        put("loud", b(loudness)); put("loud.amount", f(loudnessAmount)); put("volume", String.format(Locale.US, "%.4f", volume))
        put("lim", b(limiter)); put("lim.thr", f(limiterThresholdDb)); put("lim.rel", f(limiterReleaseMs))
        put("clarity", b(clarity)); put("clarity.preset", clarityPreset); put("clarity.amount", f(clarityAmount))
        put("dc", b(dcBlock)); put("soft", b(softSat))
        return sb.toString()
    }

    /** Nome breve del modo dell'equalizzatore. */
    fun eqLabel(): String = when (eqMode) {
        EQ_PARAMETRIC -> "EQ parametrico (${peq.count { it.on }} filtri)"
        EQ_AUTOEQ -> "AutoEQ ${autoEqName}".trim()
        else -> "EQ $bands bande"
    }

    fun withGain(i: Int, db: Float): DspSettings {
        if (i !in 0 until bands) return this
        val g = List(bands) { graphic.getOrElse(it) { 0f } }.toMutableList()
        g[i] = db.coerceIn(-GAIN, GAIN)
        return copy(graphic = g)
    }

    /** Cambia il numero di bande mantenendo la forma della curva. */
    fun withBands(n: Int): DspSettings {
        if (n !in BAND_OPTIONS || n == bands) return this
        val from = freqs.zip(List(bands) { graphic.getOrElse(it) { 0f } })
        return copy(bands = n, graphic = freqsFor(n).map { interpolate(from, it) })
    }

    /** Curva dell'equalizzatore (per i preset). */
    fun eqPart(): EqPart = EqPart(freqs, List(bands) { graphic.getOrElse(it) { 0f } }, if (preampOn) preampDb else 0f)

    /** Applica un preset sulle bande attuali (la curva viene adattata al numero di bande). */
    fun withEqPart(p: EqPart): DspSettings = copy(
        eq = true, master = true, eqMode = EQ_GRAPHIC,
        graphic = freqs.map { p.gainAt(it) },
        preampOn = p.preampDb != 0f || preampOn, preampDb = if (p.preampDb != 0f) p.preampDb else preampDb,
    )

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 3)
        o.put("master", master)
        o.put("eq", eq); o.put("bands", bands); o.put("faithful", faithful)
        o.put("graphic", JSONArray(List(bands) { graphic.getOrElse(it) { 0f }.toDouble() }))
        o.put("preOn", preampOn); o.put("pre", preampDb.toDouble())
        o.put("eqMode", eqMode); o.put("peqPre", peqPreamp.toDouble()); o.put("aeqName", autoEqName)
        o.put("peq", PeqBand.toJson(peq))
        o.put("tone", tone); o.put("bass", bassDb.toDouble()); o.put("bassF", bassFreq.toDouble()); o.put("bassQ", bassQ.toDouble())
        o.put("treble", trebleDb.toDouble()); o.put("trebleF", trebleFreq.toDouble()); o.put("trebleQ", trebleQ.toDouble())
        o.put("abass", analogBass); o.put("abassDrive", analogDrive.toDouble()); o.put("abassWarmth", analogWarmth.toDouble()); o.put("abassF", analogFreq.toDouble())
        o.put("air", air); o.put("airAmount", airAmount.toDouble())
        o.put("stereo", stereo); o.put("surround", surroundMode)
        o.put("balance", balance.toDouble()); o.put("expand", expand.toDouble()); o.put("mono", mono); o.put("crossfeed", crossfeed.toDouble())
        o.put("dbfb", dbfbMode)
        o.put("loud", loudness); o.put("loudAmount", loudnessAmount.toDouble())
        o.put("headroom", autoHeadroom)
        o.put("lim", limiter); o.put("limThr", limiterThresholdDb.toDouble()); o.put("limRel", limiterReleaseMs.toDouble())
        o.put("rg", replayGain)
        o.put("clarity", clarity); o.put("clarityPreset", clarityPreset); o.put("clarityAmount", clarityAmount.toDouble())
        o.put("dc", dcBlock); o.put("soft", softSat)
        return o
    }

    companion object {
        const val GAIN = 12f
        const val EQ_GRAPHIC = 0
        const val EQ_PARAMETRIC = 1
        const val EQ_AUTOEQ = 2
        const val PEQ_MAX = 16
        const val MIN_FREQ = 20f
        const val MAX_FREQ = 20000f
        const val TONE_MAX_DB = 10f

        /** Numero di bande disponibili (i più usati negli equalizzatori grafici). */
        val BAND_OPTIONS = listOf(3, 5, 7, 10, 15, 31)

        /** Frequenze centrali per ogni numero di bande (standard ISO dove esiste). */
        fun freqsFor(n: Int): List<Float> = when (n) {
            3 -> listOf(100f, 1000f, 10000f)
            5 -> listOf(60f, 230f, 910f, 3600f, 14000f)
            7 -> listOf(63f, 160f, 400f, 1000f, 2500f, 6300f, 16000f)
            15 -> listOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f)
            31 -> listOf(
                20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
                800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
            )
            else -> listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        }

        /** Interpolazione lineare in dB su scala logaritmica di frequenza; fuori dai punti resta costante. */
        fun interpolate(pts: List<Pair<Float, Float>>, f: Float): Float {
            if (pts.isEmpty()) return 0f
            val sorted = pts.sortedBy { it.first }
            if (f <= sorted.first().first) return sorted.first().second
            if (f >= sorted.last().first) return sorted.last().second
            for (i in 1 until sorted.size) {
                val (f1, g1) = sorted[i]
                if (f <= f1) {
                    val (f0, g0) = sorted[i - 1]
                    val t = (ln(f / f0) / ln(f1 / f0))
                    return g0 + (g1 - g0) * t
                }
            }
            return sorted.last().second
        }

        // ---- curve di filtri (per convertire preset AutoEQ e le impostazioni del parametrico)

        /** Modulo in dB del prototipo analogico di una campana o di uno shelf a frequenza f. */
        fun filterDb(type: String, f0: Float, gain: Float, q: Float, f: Float): Float {
            val a = 10.0.pow(gain / 40.0)
            val x = (f / f0).toDouble()
            val qq = q.toDouble().coerceAtLeast(0.05)
            val m = when (type) {
                "LS" -> { val rq = sqrt(a) / qq * x; a * hypot(a - x * x, rq) / hypot(1 - a * x * x, rq) }
                "HS" -> { val rq = sqrt(a) / qq * x; a * hypot(1 - a * x * x, rq) / hypot(a - x * x, rq) }
                "PK" -> { val re = 1 - x * x; hypot(re, x * a / qq) / hypot(re, x / (a * qq)) }
                else -> 1.0
            }
            return (20 * log10(m)).toFloat()
        }

        private fun JSONObject.fl(k: String, d: Float, lo: Float, hi: Float): Float {
            val v = optDouble(k, d.toDouble())
            return if (v.isFinite()) v.toFloat().coerceIn(lo, hi) else d
        }

        /** Legge le impostazioni; riconosce anche i formati delle versioni precedenti dell'app. */
        fun fromJson(s: String?): DspSettings {
            if (s.isNullOrBlank()) return DspSettings()
            return try {
                val o = JSONObject(s)
                when {
                    o.optInt("v", 1) >= 3 -> parse(o)
                    o.optInt("v", 1) == 2 -> migrateV2(o)
                    else -> migrateV1(o)
                }
            } catch (_: Exception) {
                DspSettings()
            }
        }

        private fun common(o: JSONObject, base: DspSettings): DspSettings {
            val d = DspSettings()
            return base.copy(
                master = o.optBoolean("master", base.master),
                tone = o.optBoolean("tone", true),
                bassDb = o.fl("bass", 0f, 0f, TONE_MAX_DB), bassFreq = o.fl("bassF", d.bassFreq, 20f, 500f), bassQ = o.fl("bassQ", d.bassQ, 0.1f, 2f),
                trebleDb = o.fl("treble", 0f, 0f, TONE_MAX_DB), trebleFreq = o.fl("trebleF", d.trebleFreq, 1000f, 20000f), trebleQ = o.fl("trebleQ", d.trebleQ, 0.1f, 2f),
                analogBass = o.optBoolean("abass", false), analogDrive = o.fl("abassDrive", d.analogDrive, 0f, 1f),
                analogWarmth = o.fl("abassWarmth", d.analogWarmth, 0f, 1f), analogFreq = o.fl("abassF", d.analogFreq, 20f, 120f),
                air = o.optBoolean("air", false), airAmount = o.fl("airAmount", d.airAmount, 0f, 1f),
                stereo = if (o.has("stereo")) o.optBoolean("stereo", false) else
                    o.optInt("surround", 0) > 0 || o.optDouble("balance", 0.0) != 0.0 || o.optDouble("expand", 0.0) > 0.0 ||
                        o.optDouble("crossfeed", 0.0) > 0.0 || o.optBoolean("mono", false),
                surroundMode = o.optInt("surround", 0).coerceIn(0, 3),
                balance = o.fl("balance", 0f, -1f, 1f), expand = o.fl("expand", 0f, 0f, 1f), mono = o.optBoolean("mono", false),
                crossfeed = o.fl("crossfeed", 0f, 0f, 1f), dbfbMode = o.optInt("dbfb", 0).coerceIn(0, 2),
                loudness = o.optBoolean("loud", false), loudnessAmount = o.fl("loudAmount", d.loudnessAmount, 0f, 1f),
                autoHeadroom = o.optBoolean("headroom", true),
                limiter = o.optBoolean("lim", true), limiterThresholdDb = o.fl("limThr", d.limiterThresholdDb, -12f, 0f),
                limiterReleaseMs = o.fl("limRel", d.limiterReleaseMs, 5f, 1000f),
                replayGain = o.optInt("rg", 0).coerceIn(0, 2),
                clarity = o.optBoolean("clarity", false), clarityPreset = o.optInt("clarityPreset", 0).coerceIn(0, 3),
                clarityAmount = o.fl("clarityAmount", 1f, 0f, 1f),
                dcBlock = o.optBoolean("dc", true), softSat = o.optBoolean("soft", false),
            )
        }

        private fun parse(o: JSONObject): DspSettings {
            val n = o.optInt("bands", 10).let { if (it in BAND_OPTIONS) it else 10 }
            val ga = o.optJSONArray("graphic")
            val gains = List(n) { i -> (ga?.optDouble(i, 0.0) ?: 0.0).toFloat().let { if (it.isFinite()) it.coerceIn(-GAIN, GAIN) else 0f } }
            return common(o, DspSettings()).copy(
                eq = o.optBoolean("eq", true), bands = n, graphic = gains, faithful = o.optBoolean("faithful", true),
                preampOn = o.optBoolean("preOn", false), preampDb = o.fl("pre", 0f, -12f, 12f),
                eqMode = o.optInt("eqMode", EQ_GRAPHIC).coerceIn(0, 2),
                peq = PeqBand.fromJson(o.optJSONArray("peq")),
                peqPreamp = o.fl("peqPre", 0f, -30f, 0f),
                autoEqName = o.optString("aeqName", ""),
            )
        }

        /** Curva di un elenco di filtri (tipo, f, guadagno, Q) alle frequenze date. */
        private fun curveOf(filters: List<FilterSpec>, at: List<Float>): List<Float> =
            at.map { f -> filters.sumOf { s -> filterDb(s.type, s.freq, s.gain, s.q, f).toDouble() }.toFloat().coerceIn(-GAIN, GAIN) }

        /** Versione 2: grafico a 15 bande oppure parametrico a 16 (convertito in curva sul grafico). */
        private fun migrateV2(o: JSONObject): DspSettings {
            val mode = o.optInt("eqMode", 1)
            var s = common(o, DspSettings())
            val pre = o.fl("pre", 0f, -12f, 12f)
            s = s.copy(preampOn = pre != 0f, preampDb = pre, faithful = o.optBoolean("faithful", true))
            if (mode == 2) {
                val pa = o.optJSONArray("parametric")
                val specs = ArrayList<FilterSpec>()
                if (pa != null) for (i in 0 until pa.length()) {
                    val b = pa.optJSONObject(i) ?: continue
                    if (!b.optBoolean("on", false)) continue
                    val t = when (b.optInt("type", 0)) { 0 -> "PK"; 1 -> "LS"; 2 -> "HS"; else -> continue }
                    specs.add(FilterSpec(t, b.optDouble("f", 1000.0).toFloat(), b.optDouble("g", 0.0).toFloat(), b.optDouble("q", 1.0).toFloat()))
                }
                return s.copy(eq = true, bands = 31, graphic = curveOf(specs, freqsFor(31)))
            }
            val ga = o.optJSONArray("graphic")
            val g15 = List(15) { i -> (ga?.optDouble(i, 0.0) ?: 0.0).toFloat().coerceIn(-GAIN, GAIN) }
            return s.copy(eq = mode != 0, bands = 15, graphic = g15)
        }

        /** Versione 1 (EqConfig): bande parametriche -> curva sul grafico a 31 bande. */
        private fun migrateV1(o: JSONObject): DspSettings {
            val d = DspSettings()
            val n = o.optInt("bandCount", 10)
            val fa = o.optJSONArray("freqs"); val ga = o.optJSONArray("gains"); val qa = o.optJSONArray("qs"); val ta = o.optJSONArray("types")
            val specs = ArrayList<FilterSpec>()
            if (fa != null && ga != null && fa.length() == n && ga.length() == n) {
                for (i in 0 until n) {
                    val t = when (ta?.optInt(i, 2) ?: 2) { 0 -> "LS"; 1 -> "HS"; else -> "PK" }
                    specs.add(FilterSpec(t, fa.getDouble(i).toFloat(), ga.getDouble(i).toFloat(), (qa?.optDouble(i, 1.0) ?: 1.0).toFloat()))
                }
            }
            val tv = o.optInt("toneV", 1)
            fun tone(key: String): Float {
                val raw = o.optInt(key, if (tv == 2) 50 else 0).coerceIn(0, 100)
                val db = when (tv) { 1 -> raw / 100.0 * 12.0; 2 -> (raw - 50) / 50.0 * 12.0; else -> raw / 10.0 }
                return db.toFloat().coerceIn(0f, TONE_MAX_DB)
            }
            val pre = o.optDouble("preamp", 0.0).toFloat().coerceIn(-12f, 12f)
            return d.copy(
                master = true,
                eq = o.optBoolean("enabled", false), bands = 31, graphic = curveOf(specs, freqsFor(31)),
                preampOn = pre != 0f, preampDb = pre,
                bassDb = tone("bass"), trebleDb = tone("treble"),
                limiter = o.optBoolean("limiter", true),
                limiterThresholdDb = o.optDouble("limThr", -0.5).toFloat().coerceIn(-12f, 0f),
                limiterReleaseMs = o.optDouble("limRel", 80.0).toFloat().coerceIn(5f, 1000f),
                stereo = o.optInt("balance", 0) != 0 || o.optInt("expand", 0) > 0 || o.optBoolean("mono", false),
                balance = (o.optInt("balance", 0) / 100f).coerceIn(-1f, 1f),
                expand = (o.optInt("expand", 0) / 100f).coerceIn(0f, 1f),
                mono = o.optBoolean("mono", false),
                replayGain = o.optInt("replayGain", 0).coerceIn(0, 2),
            )
        }

        /** Posizione 0..1 su scala logaritmica 20 Hz - 20 kHz. */
        fun freqToPos(f: Float): Float = (ln(f / MIN_FREQ) / ln(MAX_FREQ / MIN_FREQ)).coerceIn(0f, 1f)
    }
}

/** Un filtro di un preset testuale: tipo "PK", "LS" o "HS". */
/** Filtro del parametrico; [type] come nel motore nativo (PK, LS, HS, LP, HP, BP, notch). */
data class PeqBand(val on: Boolean = true, val type: Int = PK, val freq: Float = 1000f, val gain: Float = 0f, val q: Float = 1f) {
    val hasGain: Boolean get() = type == PK || type == LS || type == HS

    companion object {
        const val PK = 0; const val LS = 1; const val HS = 2; const val LP = 3; const val HP = 4; const val BP = 5; const val NOTCH = 6
        val TYPES = listOf(PK, LS, HS, LP, HP, BP, NOTCH)
        fun label(t: Int) = when (t) { LS -> "Shelf bassi"; HS -> "Shelf alti"; LP -> "Passa-basso"; HP -> "Passa-alto"; BP -> "Passa-banda"; NOTCH -> "Notch"; else -> "Campana" }
        fun code(t: Int) = when (t) { LS -> "LS"; HS -> "HS"; LP -> "LP"; HP -> "HP"; BP -> "BP"; NOTCH -> "NO"; else -> "PK" }

        fun toJson(l: List<PeqBand>) = JSONArray().also { a ->
            l.forEach { b -> a.put(JSONObject().put("on", b.on).put("t", b.type).put("f", b.freq.toDouble()).put("g", b.gain.toDouble()).put("q", b.q.toDouble())) }
        }

        fun fromJson(a: JSONArray?): List<PeqBand> {
            if (a == null) return emptyList()
            val out = ArrayList<PeqBand>()
            for (i in 0 until minOf(a.length(), DspSettings.PEQ_MAX)) {
                val o = a.optJSONObject(i) ?: continue
                out.add(PeqBand(
                    o.optBoolean("on", true), o.optInt("t", PK).coerceIn(0, 6),
                    o.optDouble("f", 1000.0).toFloat().coerceIn(20f, 20000f),
                    o.optDouble("g", 0.0).toFloat().coerceIn(-24f, 24f),
                    o.optDouble("q", 1.0).toFloat().coerceIn(0.1f, 20f),
                ))
            }
            return out
        }
    }
}

data class FilterSpec(val type: String, val freq: Float, val gain: Float, val q: Float)

/** Preset: una curva (frequenze e guadagni) e il preamp. Si adatta a qualsiasi numero di bande. */
data class EqPart(val freqs: List<Float>, val gains: List<Float>, val preampDb: Float) {
    fun gainAt(f: Float): Float = DspSettings.interpolate(freqs.zip(gains), f).coerceIn(-DspSettings.GAIN, DspSettings.GAIN)

    fun toJson(): JSONObject = JSONObject()
        .put("freqs", JSONArray(freqs.map { it.toDouble() }))
        .put("gains", JSONArray(gains.map { it.toDouble() }))
        .put("pre", preampDb.toDouble())

    companion object {
        fun fromJson(o: JSONObject): EqPart? {
            val fa = o.optJSONArray("freqs") ?: return null
            val ga = o.optJSONArray("gains") ?: return null
            if (fa.length() != ga.length() || fa.length() == 0) return null
            return EqPart(
                List(fa.length()) { fa.getDouble(it).toFloat() },
                List(ga.length()) { ga.getDouble(it).toFloat().coerceIn(-DspSettings.GAIN, DspSettings.GAIN) },
                o.optDouble("pre", 0.0).toFloat().coerceIn(-12f, 12f),
            )
        }
    }
}

/** Preset di fabbrica: curve sulle 15 bande ISO, adattate al numero di bande scelto. */
object GraphicPresets {
    class Preset(val name: String, private val g15: List<Float>) {
        val part: EqPart get() = EqPart(DspSettings.freqsFor(15), g15, 0f)
    }

    val all = listOf(
        Preset("Piatto", List(15) { 0f }),
        Preset("Bassi", listOf(6f, 6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Alti", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5f, 5f)),
        Preset("Voce", listOf(-3f, -3f, -2f, -1f, 0f, 1f, 2f, 3f, 3.5f, 3f, 2f, 1f, 0f, -1f, -1f)),
        Preset("Rock", listOf(4f, 4f, 3f, 1.5f, 0f, -1f, -1.5f, -1f, 0f, 1f, 2.5f, 3.5f, 4f, 4f, 3.5f)),
        Preset("Pop", listOf(-1f, 0f, 1f, 2.5f, 3.5f, 3.5f, 2f, 0.5f, -0.5f, -0.5f, 0f, 1f, 1.5f, 2f, 2f)),
        Preset("Jazz", listOf(3f, 3f, 2f, 1.5f, 0f, -1f, -1f, 0f, 1f, 1.5f, 2f, 2.5f, 3f, 3f, 3f)),
        Preset("Classica", listOf(3.5f, 3f, 2.5f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3f, 3.5f)),
        Preset("Elettronica", listOf(5f, 5f, 4f, 2.5f, 0.5f, -1f, -1f, 0f, 0.5f, 1.5f, 2.5f, 3f, 3.5f, 4f, 4f)),
        Preset("Loudness", listOf(6f, 5.5f, 4.5f, 3f, 1f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1.5f, 3f, 4f, 4.5f, 5f)),
        Preset("Podcast", listOf(-6f, -5f, -3f, -1f, 0f, 1f, 2f, 3f, 3f, 2.5f, 2f, 1f, 0f, -2f, -4f)),
    )
}
