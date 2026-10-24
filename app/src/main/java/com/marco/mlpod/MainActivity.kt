package com.marco.mlpod

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.marco.mlpod.ui.Actions
import com.marco.mlpod.ui.IpodApp
import com.marco.mlpod.util.AppLog

class MainActivity : ComponentActivity(), Actions {

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                AppLog.w("Libreria", "Permesso persistente non ottenuto", e)
            }
            val app = application as MLPodApp
            app.settings.addFolder(uri.toString())
            AppLog.i("Libreria", "Cartella aggiunta: $uri")
            app.env.startScan(false)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("App", "Permesso notifiche: ${if (granted) "concesso" else "negato"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val app = application as MLPodApp
        val env = app.env
        env.actions = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { IpodApp(env) }
    }

    override fun onDestroy() {
        val env = (application as MLPodApp).env
        if (env.actions === this) env.actions = null
        super.onDestroy()
    }

    // ---- Actions

    override fun pickFolder() {
        try {
            folderPicker.launch(null)
        } catch (e: Exception) {
            AppLog.e("Libreria", "Selettore cartelle non disponibile", e)
        }
    }

    override fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLog.w("App", "Impossibile aprire $url", e)
        }
    }

    override fun shareLog() {
        try {
            val file = AppLog.exportFile(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Log di MLPod")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Condividi log"))
        } catch (e: Exception) {
            AppLog.e("App", "Condivisione del log non riuscita", e)
        }
    }
}
