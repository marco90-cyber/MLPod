package com.marco.mlpod.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlpod.audio.DspSettings
import com.marco.mlpod.audio.EqPart
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Preferenze osservabili da Compose e salvate su disco. */
class BoolPref(private val sp: SharedPreferences, private val key: String, def: Boolean) :
    ReadWriteProperty<Any?, Boolean> {
    private var value by mutableStateOf(sp.getBoolean(key, def))
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        this.value = value
        sp.edit().putBoolean(key, value).apply()
    }
}

class IntPref(private val sp: SharedPreferences, private val key: String, def: Int) :
    ReadWriteProperty<Any?, Int> {
    private var value by mutableStateOf(sp.getInt(key, def))
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        this.value = value
        sp.edit().putInt(key, value).apply()
    }
}

class FloatPref(private val sp: SharedPreferences, private val key: String, def: Float) :
    ReadWriteProperty<Any?, Float> {
    private var value by mutableStateOf(sp.getFloat(key, def))
    override fun getValue(thisRef: Any?, property: KProperty<*>): Float = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
        this.value = value
        sp.edit().putFloat(key, value).apply()
    }
}

class StringPref(private val sp: SharedPreferences, private val key: String, def: String) :
    ReadWriteProperty<Any?, String> {
    private var value by mutableStateOf(sp.getString(key, def) ?: def)
    override fun getValue(thisRef: Any?, property: KProperty<*>): String = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        this.value = value
        sp.edit().putString(key, value).apply()
    }
}

/** Preset dell'equalizzatore salvato dall'utente (configurazione completa). */
data class UserPreset(val name: String, val part: EqPart)

class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mlpod_settings", Context.MODE_PRIVATE)

    // Aspetto
    var bodyColorId by StringPref(sp, "body_color", "argento")
    /** 0 = giorno, 1 = notte, 2 = segui il sistema */
    var themeMode by IntPref(sp, "theme_mode", 2)
    var coverInMenu by BoolPref(sp, "cover_in_menu", true)
    var keepScreenOn by BoolPref(sp, "keep_screen_on", false)

    /** Sensibilità dell'analizzatore (offset di visualizzazione in dB, -24..+24) */
    var analyzerSens by IntPref(sp, "analyzer_sens", 0)

    // Suoni e tatto
    var hapticScreen by BoolPref(sp, "haptic_screen", true)
    var soundScreen by BoolPref(sp, "sound_screen", true)
    var hapticWheel by BoolPref(sp, "haptic_wheel", true)
    var soundWheel by BoolPref(sp, "sound_wheel", true)

    /** 0 = leggera, 1 = media, 2 = forte */
    var hapticStrength by IntPref(sp, "haptic_strength", 1)

    /** 0 = basso, 1 = medio, 2 = alto */
    var clickVolume by IntPref(sp, "click_volume", 1)

    // Libreria
    var scanOnStart by BoolPref(sp, "scan_on_start", true)
    private var foldersRaw by StringPref(sp, "folders", "")

    val folders: List<String>
        get() = foldersRaw.split('\n').filter { it.isNotBlank() }

    fun addFolder(uri: String) {
        if (uri !in folders) foldersRaw = (folders + uri).joinToString("\n")
    }

    fun removeFolder(uri: String) {
        foldersRaw = folders.filter { it != uri }.joinToString("\n")
    }

    // Riproduzione
    var shuffle by BoolPref(sp, "shuffle", false)

    /** 0 = no, 1 = tutto, 2 = brano singolo */
    var repeatMode by IntPref(sp, "repeat", 0)
    /** Uscita adattiva: bit e kHz scelti in base al DAC o alle casse collegate */
    var adaptiveOutput by BoolPref(sp, "adaptive_output", true)
    var volume by FloatPref(sp, "volume", 1f)

    // Analizzatore
    var meterNonLinear by BoolPref(sp, "meter_nonlinear", true)
    var meterPpm by BoolPref(sp, "meter_ppm", true)
    var meterRef by IntPref(sp, "meter_ref", -12)

    // Debug
    var verboseLog by BoolPref(sp, "verbose_log", false)

    // Equalizzatore
    /** Loudness misurata dei brani senza tag ReplayGain. */
    val loudnessCache = com.marco.mlpod.audio.LoudnessCache(sp)

    private var dspState by mutableStateOf(
        DspSettings.fromJson(sp.getString("dsp", null) ?: sp.getString("eq", null))
    )

    var onDspChanged: ((DspSettings) -> Unit)? = null

    var dsp: DspSettings
        get() = dspState
        set(v) {
            dspState = v
            sp.edit().putString("dsp", v.toJson().toString()).apply()
            onDspChanged?.invoke(v)
        }

    fun updateDsp(block: (DspSettings) -> DspSettings) {
        dsp = block(dspState)
    }

    // Preset utente (solo la parte equalizzatore)
    private var presetsRaw by StringPref(sp, "eq_user_presets", "[]")

    val userPresets: List<UserPreset>
        get() = try {
            val a = JSONArray(presetsRaw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val p = o.optJSONObject("part")
                val part = p?.let { EqPart.fromJson(it) }
                    ?: p?.let { old -> legacyPart(old) }  // preset della versione 2 (15 bande)
                    ?: o.optString("cfg", "").takeIf { it.isNotEmpty() }?.let { DspSettings.fromJson(it).eqPart() }  // versione 1
                part?.let { UserPreset(o.getString("name"), it) }
            }
        } catch (_: Exception) {
            emptyList()
        }

    private fun legacyPart(o: JSONObject): EqPart? {
        val ga = o.optJSONArray("graphic") ?: return null
        val f = DspSettings.freqsFor(15)
        return EqPart(f, List(15) { ga.optDouble(it, 0.0).toFloat() }, o.optDouble("pre", 0.0).toFloat())
    }

    /** Salva (o sostituisce, se il nome esiste già) un preset. */
    fun saveUserPreset(name: String, part: EqPart) {
        val n = name.trim().take(24)
        if (n.isEmpty()) return
        writePresets(userPresets.filter { !it.name.equals(n, ignoreCase = true) } + UserPreset(n, part))
    }

    fun deleteUserPreset(name: String) {
        writePresets(userPresets.filter { it.name != name })
    }

    private fun writePresets(list: List<UserPreset>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("name", p.name).put("part", p.part.toJson()))
        presetsRaw = a.toString()
    }

}
