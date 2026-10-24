package com.marco.mlpod.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Elaborazione DSP in tempo reale: preamp + manopola bassi + N bande parametriche +
 * manopola alti, tutto con filtri biquad in doppia precisione.
 *
 * Ingressi supportati: PCM 16 bit, 24 bit, 32 bit e float.
 * Uscita: 16 bit se l'ingresso è 16 bit, altrimenti float (qualità Hi-Res).
 */
class EqAudioProcessor(private val analyzer: Analyzer) : BaseAudioProcessor() {

    @Volatile
    private var pending: EqConfig = EqConfig()

    @Volatile
    private var configVersion = 0

    private var appliedVersion = -1
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    private var stageCount = 0
    private var coefs = DoubleArray(0)
    private var state = DoubleArray(0)
    private var preGain = 1.0
    private var bypass = true

    fun setConfig(config: EqConfig) {
        pending = config
        configVersion++
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT &&
            enc != C.ENCODING_PCM_24BIT && enc != C.ENCODING_PCM_32BIT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = enc
        analyzer.sampleRate = sampleRate
        appliedVersion = -1
        stageCount = 0
        val outEnc = if (enc == C.ENCODING_PCM_16BIT) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT
        return AudioProcessor.AudioFormat(sampleRate, channels, outEnc)
    }

    override fun onFlush() {
        java.util.Arrays.fill(state, 0.0)
    }

    override fun onReset() {
        stageCount = 0
        coefs = DoubleArray(0)
        state = DoubleArray(0)
        appliedVersion = -1
    }

    private fun refreshIfNeeded() {
        val v = configVersion
        if (v == appliedVersion && stageCount > 0) return
        val cfg = pending
        val list = EqMath.stages(cfg, sampleRate.toDouble())
        val flat = DoubleArray(list.size * 5)
        for ((i, c) in list.withIndex()) {
            flat[i * 5] = c.b0
            flat[i * 5 + 1] = c.b1
            flat[i * 5 + 2] = c.b2
            flat[i * 5 + 3] = c.a1
            flat[i * 5 + 4] = c.a2
        }
        if (list.size != stageCount || state.size != channels * list.size * 2) {
            state = DoubleArray(channels * list.size * 2)
        }
        coefs = flat
        stageCount = list.size
        preGain = if (cfg.enabled) 10.0.pow(cfg.preampDb / 20.0) else 1.0
        bypass = kotlin.math.abs(preGain - 1.0) < 1e-6 && list.all { it === EqMath.IDENTITY }
        appliedVersion = v
    }

    private fun filter(x0: Double, channel: Int): Double {
        var x = x0 * preGain
        val n = stageCount
        var s = channel * n * 2
        var o = 0
        val co = coefs
        val st = state
        for (k in 0 until n) {
            val z1 = st[s]
            val z2 = st[s + 1]
            val y = co[o] * x + z1
            st[s] = co[o + 1] * x - co[o + 3] * y + z2
            st[s + 1] = co[o + 2] * x - co[o + 4] * y
            x = y
            s += 2
            o += 5
        }
        return x
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        refreshIfNeeded()

        val ch = channels
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val frames = remaining / (bytesPerSample * ch)
        val samples = frames * ch

        val tap = analyzer.enabled
        var fl = 0f
        var fr = 0f

        if (encoding == C.ENCODING_PCM_16BIT) {
            val out = replaceOutputBuffer(samples * 2)
            for (f in 0 until frames) {
                for (c in 0 until ch) {
                    val raw = input.getShort()
                    if (bypass) {
                        out.putShort(raw)
                        if (tap) {
                            val v = raw / 32768f
                            if (c == 0) fl = v else if (c == 1) fr = v
                        }
                    } else {
                        val x = raw.toInt() / 32768.0
                        val y = filter(x, c).coerceIn(-1.0, 1.0)
                        out.putShort((y * 32767.0).roundToInt().toShort())
                        if (tap) {
                            if (c == 0) fl = y.toFloat() else if (c == 1) fr = y.toFloat()
                        }
                    }
                }
                if (tap) analyzer.push(fl, if (ch > 1) fr else fl)
            }
            out.flip()
        } else {
            val out = replaceOutputBuffer(samples * 4)
            for (f in 0 until frames) {
                for (c in 0 until ch) {
                    val x: Double = when (encoding) {
                        C.ENCODING_PCM_FLOAT -> input.getFloat().toDouble()
                        C.ENCODING_PCM_24BIT -> {
                            val b0 = input.get().toInt() and 0xFF
                            val b1 = input.get().toInt() and 0xFF
                            val b2 = input.get().toInt()
                            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608.0
                        }
                        else -> input.getInt() / 2147483648.0
                    }
                    val y = if (bypass) x else filter(x, c)
                    val yf = y.coerceIn(-1.0, 1.0).toFloat()
                    out.putFloat(yf)
                    if (tap) {
                        if (c == 0) fl = yf else if (c == 1) fr = yf
                    }
                }
                if (tap) analyzer.push(fl, if (ch > 1) fr else fl)
            }
            out.flip()
        }
        inputBuffer.position(inputBuffer.limit())
    }
}
