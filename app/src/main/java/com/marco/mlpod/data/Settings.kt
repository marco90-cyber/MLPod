package com.marco.mlpod.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlpod.audio.EqConfig
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
data class UserPreset(val name: String, val config: EqConfig)

class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mlpod_settings", Context.MODE_PRIVATE)

    // Aspetto
    var bodyColorId by StringPref(sp, "body_color", "argento")
    var coverInMenu by BoolPref(sp, "cover_in_menu", true)
    var keepScreenOn by BoolPref(sp, "keep_screen_on", false)

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
    var hiResOutput by BoolPref(sp, "hires_output", true)
    var volume by FloatPref(sp, "volume", 1f)

    // Analizzatore
    var meterNonLinear by BoolPref(sp, "meter_nonlinear", true)
    var meterPpm by BoolPref(sp, "meter_ppm", true)
    var meterRef by IntPref(sp, "meter_ref", -12)

    // Debug
    var verboseLog by BoolPref(sp, "verbose_log", false)

    // Equalizzatore
    private var eqState by mutableStateOf(EqConfig.fromJson(sp.getString("eq", null)))

    var onEqChanged: ((EqConfig) -> Unit)? = null

    var eq: EqConfig
        get() = eqState
        set(v) {
            eqState = v
            sp.edit().putString("eq", v.toJson()).apply()
            onEqChanged?.invoke(v)
        }

    fun updateEq(block: (EqConfig) -> EqConfig) {
        eq = block(eqState)
    }

    // Preset utente
    private var presetsRaw by StringPref(sp, "eq_user_presets", "[]")

    val userPresets: List<UserPreset>
        get() = try {
            val a = JSONArray(presetsRaw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                UserPreset(o.getString("name"), EqConfig.fromJson(o.getString("cfg")))
            }
        } catch (_: Exception) {
            emptyList()
        }

    /** Salva (o sostituisce, se il nome esiste già) un preset con la configurazione attuale. */
    fun saveUserPreset(name: String, cfg: EqConfig) {
        val n = name.trim().take(24)
        if (n.isEmpty()) return
        val list = userPresets.filter { !it.name.equals(n, ignoreCase = true) } + UserPreset(n, cfg.copy(enabled = true))
        writePresets(list)
    }

    fun deleteUserPreset(name: String) {
        writePresets(userPresets.filter { it.name != name })
    }

    private fun writePresets(list: List<UserPreset>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("name", p.name).put("cfg", p.config.toJson()))
        presetsRaw = a.toString()
    }
}
