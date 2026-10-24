package com.marco.mlpod

import android.app.Application
import com.marco.mlpod.audio.AudioEngine
import com.marco.mlpod.data.Library
import com.marco.mlpod.data.Settings
import com.marco.mlpod.feedback.Feedback
import com.marco.mlpod.ui.AppEnv
import com.marco.mlpod.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MLPodApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var settings: Settings
        private set
    lateinit var library: Library
        private set
    lateinit var engine: AudioEngine
        private set
    lateinit var feedback: Feedback
        private set

    /** Stato dell'interfaccia: sopravvive alla ricreazione dell'Activity. */
    val env: AppEnv by lazy { AppEnv(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        settings = Settings(this)
        AppLog.verbose = settings.verboseLog
        library = Library(this)
        feedback = Feedback(this, settings)
        engine = AudioEngine(this, settings, library)
        engine.connectService()

        appScope.launch {
            library.load()
            if (settings.scanOnStart && settings.folders.isNotEmpty()) {
                library.rescan(settings.folders, rebuild = false)
            }
        }
    }
}
