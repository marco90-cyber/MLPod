package com.marco.mlpod.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.marco.mlpod.MLPodApp
import com.marco.mlpod.data.Track
import com.marco.mlpod.util.AppLog
import kotlinx.coroutines.launch

enum class Overlay { None, Equalizer, Log, Output }

/** Azioni che richiedono l'Activity (selettore cartelle, browser, condivisione). */
interface Actions {
    fun pickFolder()
    fun openUrl(url: String)
    fun shareLog()
}

class MenuItem(
    val label: String,
    val value: String? = null,
    val chevron: Boolean = false,
    val swatch: Color? = null,
    val sub: String? = null,
    val track: Track? = null,
    val albumKey: String? = null,
    val previewTitle: String? = null,
    val previewSub: String? = null,
    val dimmed: Boolean = false,
    val onClick: () -> Unit = {},
)

sealed class Screen

class MenuScreen(
    val title: String,
    val cache: Boolean = false,
    val split: Boolean = false,
    val build: AppEnv.() -> List<MenuItem>,
) : Screen()

object NowPlayingScreen : Screen()

object AnalyzerScreen : Screen()

class Entry(val screen: Screen) {
    var selected by mutableIntStateOf(0)
    val listState = LazyListState()

    /** Voci attualmente mostrate (aggiornate dalla UI, lette dalla ghiera). */
    var items: List<MenuItem> = emptyList()
}

class Nav {
    val stack = mutableStateListOf<Entry>()

    val top: Entry get() = stack.last()

    fun push(screen: Screen) {
        if (stack.isNotEmpty() && stack.last().screen === screen) return
        stack.add(Entry(screen))
    }

    fun pop(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** Stato globale dell'interfaccia: navigazione, overlay e comandi della ghiera. */
class AppEnv(val app: MLPodApp) {
    val settings get() = app.settings
    val library get() = app.library
    val engine get() = app.engine
    val feedback get() = app.feedback

    var actions: Actions? = null

    val nav = Nav()
    var overlay by mutableStateOf(Overlay.None)

    /** In "In riproduzione": la ghiera regola il volume (false) oppure scorre il brano (true). */
    var scrubMode by mutableStateOf(false)
    var showVolume by mutableStateOf(false)
    var volumeToken by mutableIntStateOf(0)

    /** Banda dell'analizzatore selezionata (0..30, 17 = 1 kHz) e richiesta di azzeramento picchi. */
    var analyzerBand by mutableIntStateOf(17)
    var analyzerResetToken by mutableIntStateOf(0)

    val wheel = WheelController(this)

    init {
        nav.stack.add(Entry(rootScreen()))
    }

    fun startScan(rebuild: Boolean = false) {
        app.appScope.launch {
            library.rescan(settings.folders, rebuild)
        }
    }

    fun openAnalyzer() {
        nav.push(AnalyzerScreen)
    }

    fun openNowPlaying() {
        scrubMode = false
        nav.push(NowPlayingScreen)
    }

    fun playFrom(list: List<Track>, index: Int) {
        engine.playQueue(list, index, shuffle = false)
        openNowPlaying()
    }

    fun shuffleAll() {
        val all = library.tracks
        if (all.isEmpty()) return
        engine.playQueue(all, (all.indices).random(), shuffle = true)
        openNowPlaying()
    }

    fun shuffleAlbums() {
        val albums = library.albums
        if (albums.isEmpty()) return
        val queue = albums.shuffled().flatMap { it.tracks }
        engine.playQueue(queue, 0, shuffle = false)
        openNowPlaying()
    }

    fun log(msg: String) = AppLog.d("UI", msg)
}

/** Comandi della ghiera: gli stessi dell'iPod. */
class WheelController(private val env: AppEnv) {

    /** @return true se qualcosa è cambiato (serve per il feedback tattile/sonoro). */
    fun onStep(delta: Int): Boolean {
        val top = env.nav.top
        return when (top.screen) {
            is MenuScreen -> {
                val size = top.items.size
                if (size == 0) return false
                val n = (top.selected + delta).coerceIn(0, size - 1)
                if (n != top.selected) {
                    top.selected = n
                    env.log("Ghiera: voce $n")
                    true
                } else false
            }
            NowPlayingScreen -> {
                if (env.scrubMode) {
                    env.engine.seekBy(delta * 5000L)
                } else {
                    env.engine.changeVolume(delta / 40f)
                    env.volumeToken++
                }
                true
            }
            AnalyzerScreen -> {
                val n = (env.analyzerBand + delta).coerceIn(0, 30)
                if (n != env.analyzerBand) {
                    env.analyzerBand = n
                    true
                } else false
            }
        }
    }

    fun onCenter() {
        val top = env.nav.top
        when (top.screen) {
            is MenuScreen -> {
                val item = top.items.getOrNull(top.selected) ?: return
                env.log("Ghiera: selezione \"${item.label}\"")
                item.onClick()
            }
            NowPlayingScreen -> {
                env.scrubMode = !env.scrubMode
                env.volumeToken++
            }
            AnalyzerScreen -> env.analyzerResetToken++
        }
    }

    fun onMenu() {
        if (env.overlay != Overlay.None) {
            env.overlay = Overlay.None
            return
        }
        env.scrubMode = false
        env.nav.pop()
    }

    fun onMenuLong() {
        env.overlay = Overlay.None
        env.scrubMode = false
        env.nav.popToRoot()
    }

    fun onPlayPause() {
        val e = env.engine
        if (e.currentTrack == null && env.library.tracks.isNotEmpty()) {
            env.shuffleAll()
        } else {
            e.togglePlayPause()
        }
    }

    fun onNext() = env.engine.next()

    fun onPrev() = env.engine.previous()

    /** Tenendo premuto avanti/indietro si scorre il brano, sempre più veloce. */
    fun onSeekHold(direction: Int, heldMs: Long) {
        val step = if (heldMs > 2500) 12000L else 5000L
        env.engine.seekBy(direction * step)
    }
}
