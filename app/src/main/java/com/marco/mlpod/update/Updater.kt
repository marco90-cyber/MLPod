package com.marco.mlpod.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlpod.BuildConfig
import com.marco.mlpod.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Controlla l'ultima release su GitHub (tag build-N) e la confronta con la build installata. */
object Updater {
    private const val API = "https://api.github.com/repos/marco90-cyber/MLPod/releases/latest"

    class Release(val build: Int, val name: String, val apkUrl: String?, val pageUrl: String)

    var status by mutableStateOf("Tocca per controllare")
        private set
    var checking by mutableStateOf(false)
        private set
    var latest by mutableStateOf<Release?>(null)
        private set

    val updateAvailable: Boolean get() = (latest?.build ?: 0) > BuildConfig.VERSION_CODE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun check() {
        if (checking) return
        checking = true
        status = "Controllo in corso…"
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { fetch() }
                latest = r
                status = if (r.build > BuildConfig.VERSION_CODE) "Disponibile: ${r.name}"
                else "Nessun aggiornamento (hai l'ultima)"
                AppLog.i("Aggiornamenti", "Installata ${BuildConfig.VERSION_CODE}, ultima ${r.build}")
            } catch (e: Exception) {
                status = "Controllo non riuscito (connessione?)"
                AppLog.w("Aggiornamenti", "Controllo fallito", e)
            } finally {
                checking = false
            }
        }
    }

    private fun fetch(): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MLPod/${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            val build = Regex("\\d+").findAll(tag).lastOrNull()?.value?.toIntOrNull() ?: 0
            val assets = o.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            return Release(build, o.optString("name", tag), apk, o.optString("html_url"))
        } finally {
            c.disconnect()
        }
    }
}
