package com.marco.mlpod.audio

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.marco.mlpod.data.Library
import com.marco.mlpod.data.Settings
import com.marco.mlpod.data.Track
import com.marco.mlpod.util.AppLog

/**
 * Motore audio condiviso da interfaccia e servizio in background.
 * ExoPlayer + equalizzatore DSP + uscita Hi-Res (float a 32 bit).
 */
class AudioEngine(
    private val app: Application,
    private val settings: Settings,
    private val library: Library,
) {
    val analyzer = Analyzer()
    val eqProcessor = EqAudioProcessor(analyzer)

    val player: ExoPlayer

    var currentTrack by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var hiResNow by mutableStateOf(false)
        private set
    var audioInfo by mutableStateOf("")
        private set
    var queueIndex by mutableIntStateOf(0)
        private set
    var queueSize by mutableIntStateOf(0)
        private set
    var shuffleOn by mutableStateOf(settings.shuffle)
        private set
    var repeatMode by mutableIntStateOf(settings.repeatMode)
        private set
    var volume by mutableStateOf(settings.volume)
        private set

    private var queue: List<Track> = emptyList()
    private var consecutiveErrors = 0
    private var runtimeSampleRate = 0
    private var runtimePcmBits = 0
    private var runtimeBitrate = 0
    private var controllerFuture: Any? = null

    init {
        val floatOutput = settings.hiResOutput
        val factory = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(floatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(eqProcessor))
                    .build()
            }
        }
        player = ExoPlayer.Builder(app, factory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.shuffleModeEnabled = settings.shuffle
        player.repeatMode = when (settings.repeatMode) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.volume = settings.volume.coerceIn(0f, 1f)

        eqProcessor.setConfig(settings.eq)
        settings.onEqChanged = { cfg -> eqProcessor.setConfig(cfg) }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                AppLog.d("Audio", "isPlaying=$isPlayingNow")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrent()
            }

            override fun onTracksChanged(tracks: Tracks) {
                for (g in tracks.groups) {
                    if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected && g.length > 0) {
                        val f = g.getTrackFormat(0)
                        runtimeSampleRate = if (f.sampleRate > 0) f.sampleRate else 0
                        runtimeBitrate = if (f.bitrate > 0) f.bitrate / 1000 else 0
                        runtimePcmBits = when (f.pcmEncoding) {
                            C.ENCODING_PCM_16BIT -> 16
                            C.ENCODING_PCM_24BIT -> 24
                            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                            else -> 0
                        }
                        AppLog.i(
                            "Audio",
                            "Formato: ${f.sampleMimeType} ${f.sampleRate} Hz, ${f.channelCount} ch, " +
                                "pcm=${f.pcmEncoding}, bitrate=${f.bitrate}"
                        )
                        break
                    }
                }
                updateCurrent()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleOn = shuffleModeEnabled
                settings.shuffle = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(mode: Int) {
                val v = when (mode) {
                    Player.REPEAT_MODE_ALL -> 1
                    Player.REPEAT_MODE_ONE -> 2
                    else -> 0
                }
                repeatMode = v
                settings.repeatMode = v
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("Audio", "Errore di riproduzione: ${error.errorCodeName}", error)
                consecutiveErrors++
                val bad = currentTrack
                if (bad != null) AppLog.w("Audio", "Brano non riproducibile: ${bad.title} (${bad.formatLabel})")
                if (player.hasNextMediaItem() && consecutiveErrors < 10) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    player.stop()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) consecutiveErrors = 0
            }
        })
        AppLog.i(
            "Audio",
            "Motore audio pronto (uscita ${if (floatOutput) "Hi-Res float 32 bit" else "standard"})"
        )
    }

    /** Collega il servizio in background (notifica, controlli multimediali). */
    fun connectService() {
        try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(app, token).buildAsync()
        } catch (e: Throwable) {
            AppLog.e("Audio", "Servizio di riproduzione non collegato", e)
        }
    }

    private fun updateCurrent() {
        val idx = player.currentMediaItemIndex
        val t = queue.getOrNull(idx)
        currentTrack = t
        queueIndex = idx
        queueSize = queue.size
        if (t == null) {
            hiResNow = false
            audioInfo = ""
            return
        }
        val sr = if (runtimeSampleRate > 0) runtimeSampleRate else t.sampleRate
        val bits = if (t.bits > 0) t.bits else runtimePcmBits
        hiResNow = t.isHiResFile || sr > 48000 || runtimePcmBits > 16
        val parts = ArrayList<String>()
        parts.add(t.formatLabel)
        if (sr > 0) parts.add(if (sr % 1000 == 0) "${sr / 1000} kHz" else String.format("%.1f kHz", sr / 1000.0))
        if (bits > 0) parts.add("$bits bit")
        val br = if (t.bitrateKbps > 0) t.bitrateKbps else runtimeBitrate
        if (br > 0) parts.add("$br kbps")
        audioInfo = parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ comandi

    private fun toMediaItem(t: Track): MediaItem {
        val cover = library.coverFile(t.albumKey)
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setAlbumTitle(t.album)
        if (cover != null) meta.setArtworkUri(Uri.fromFile(cover))
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(t.uri)
            .setMediaMetadata(meta.build())
            .build()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        queue = tracks
        runtimeSampleRate = 0
        runtimePcmBits = 0
        runtimeBitrate = 0
        consecutiveErrors = 0
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(tracks.map { toMediaItem(it) }, start, 0L)
        player.prepare()
        player.play()
        updateCurrent()
        AppLog.i("Audio", "Coda di ${tracks.size} brani, avvio da #$start (${tracks[start].title})")
    }

    fun togglePlayPause() {
        if (player.mediaItemCount == 0) return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun next() {
        if (player.mediaItemCount > 0) player.seekToNext()
    }

    fun previous() {
        if (player.mediaItemCount > 0) player.seekToPrevious()
    }

    fun seekBy(deltaMs: Long) {
        val dur = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (dur > 0) target.coerceAtMost(dur - 100) else target)
    }

    fun seekToFraction(frac: Float) {
        val dur = player.duration
        if (dur > 0) player.seekTo((dur * frac.coerceIn(0f, 1f)).toLong())
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun changeVolume(delta: Float) {
        val v = (player.volume + delta).coerceIn(0f, 1f)
        player.volume = v
        volume = v
        settings.volume = v
    }
}
