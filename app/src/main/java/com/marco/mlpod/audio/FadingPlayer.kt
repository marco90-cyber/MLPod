package com.marco.mlpod.audio

import android.os.Handler
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos

/**
 * Player con dissolvenze brevi su play, pausa, salti e cambio brano a mano: niente "click" quando
 * l'onda viene tagliata a metà. È il player dato alla sessione multimediale, quindi le dissolvenze
 * valgono anche per notifica, cuffie e Bluetooth.
 *
 * La dissolvenza agisce sul volume di ExoPlayer (applicato dal mixer di Android con una sua rampa),
 * con una curva a S (coseno rialzato) che parte e arriva senza scalini.
 */
class FadingPlayer(
    private val exo: ExoPlayer,
    /** Dissolvenze attive. */
    private val enabled: () -> Boolean,
    /** Volume dell'app (0..1) su cui si applica la dissolvenza. */
    private val baseVolume: () -> Float,
) : ForwardingPlayer(exo) {

    private val handler = Handler(exo.applicationLooper)
    private var fade = 1f
    private var job: Runnable? = null

    /** True mentre il volume scende prima di una pausa (il player risulta ancora in riproduzione). */
    var pausing = false
        private set

    /** Riapplica il volume (dopo un cambio del volume dell'app). */
    fun refreshVolume() {
        exo.volume = (baseVolume() * fade).coerceIn(0f, 1f)
    }

    private fun cancel() {
        job?.let { handler.removeCallbacks(it) }
        job = null
        pausing = false
    }

    private fun ramp(to: Float, ms: Int, then: (() -> Unit)? = null) {
        job?.let { handler.removeCallbacks(it) }
        val from = fade
        val steps = (ms / STEP_MS).coerceAtLeast(1)
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i++
                val t = i.toFloat() / steps
                fade = from + (to - from) * (0.5 - 0.5 * cos(PI * t)).toFloat()
                refreshVolume()
                if (i < steps) {
                    handler.postDelayed(this, STEP_MS.toLong())
                } else {
                    fade = to
                    refreshVolume()
                    job = null
                    then?.invoke()
                }
            }
        }
        job = r
        handler.post(r)
    }

    /** Aspetta che il player suoni davvero (dopo un salto deve ricaricare), poi alza il volume. */
    private fun fadeInWhenReady(ms: Int) {
        job?.let { handler.removeCallbacks(it) }
        var waited = 0
        val r = object : Runnable {
            override fun run() {
                val ready = exo.playbackState == Player.STATE_READY && exo.isPlaying
                if (ready || waited >= READY_TIMEOUT_MS || !exo.playWhenReady) {
                    job = null
                    if (exo.playWhenReady) ramp(1f, ms) else { fade = 1f; refreshVolume() }
                } else {
                    waited += STEP_MS
                    handler.postDelayed(this, STEP_MS.toLong())
                }
            }
        }
        job = r
        handler.post(r)
    }

    private fun direct() {
        cancel()
        fade = 1f
        refreshVolume()
    }

    override fun play() {
        if (!enabled()) { direct(); super.play(); return }
        pausing = false
        val resuming = !exo.playWhenReady
        if (resuming) { cancel(); fade = 0f; refreshVolume() }
        super.play()
        if (resuming) fadeInWhenReady(FADE_IN_MS) else ramp(1f, FADE_IN_MS)
    }

    override fun pause() {
        if (!enabled() || !exo.isPlaying) { direct(); super.pause(); return }
        pausing = true
        ramp(0f, FADE_OUT_MS) {
            pausing = false
            exo.pause()
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    private fun fadedSeek(action: () -> Unit) {
        if (!enabled() || !exo.isPlaying) {
            if (!enabled()) direct()
            action()
            return
        }
        pausing = false
        ramp(0f, SEEK_OUT_MS) {
            action()
            fadeInWhenReady(SEEK_IN_MS)
        }
    }

    override fun seekTo(positionMs: Long) = fadedSeek { super.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = fadedSeek { super.seekTo(mediaItemIndex, positionMs) }
    override fun seekToDefaultPosition() = fadedSeek { super.seekToDefaultPosition() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) = fadedSeek { super.seekToDefaultPosition(mediaItemIndex) }
    override fun seekBack() = fadedSeek { super.seekBack() }
    override fun seekForward() = fadedSeek { super.seekForward() }
    override fun seekToNext() = fadedSeek { super.seekToNext() }
    override fun seekToPrevious() = fadedSeek { super.seekToPrevious() }
    override fun seekToNextMediaItem() = fadedSeek { super.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() = fadedSeek { super.seekToPreviousMediaItem() }

    companion object {
        private const val STEP_MS = 10
        private const val FADE_IN_MS = 220
        private const val FADE_OUT_MS = 140
        private const val SEEK_OUT_MS = 60
        private const val SEEK_IN_MS = 160
        private const val READY_TIMEOUT_MS = 2000
    }
}
