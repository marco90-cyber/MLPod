package com.marco.mlpod.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import com.marco.mlpod.util.AppLog

/**
 * Modalit\u00e0 DAC: su Android 14+ chiede al sistema di inviare al DAC USB il formato del brano
 * senza passare dal mixer (mixer "bit-perfect"), se il DAC lo dichiara. Niente ricampionamento di Android.
 */
class DacController(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    var enabled: Boolean = false

    var status by mutableStateOf("Spenta")
        private set

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun usbDevice(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            OutputMonitor.kindOf(it.type) == OutKind.Usb
        }

    /** Chiamata prima che si apra l'AudioTrack: [encoding] \u00e8 una costante C.ENCODING_PCM_*. */
    fun apply(sampleRate: Int, channels: Int, encoding: Int) {
        if (Build.VERSION.SDK_INT < 34) {
            status = if (enabled) "Richiede Android 14 o successivo" else "Spenta"
            return
        }
        try {
            val dev = usbDevice()
            if (dev == null) {
                status = if (enabled) "Nessun DAC USB collegato" else "Spenta"
                return
            }
            if (!enabled) {
                am.clearPreferredMixerAttributes(attrs, dev)
                status = "Spenta"
                return
            }
            val enc = when (encoding) {
                C.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                else -> {
                    status = "Formato non supportato in modo diretto"
                    return
                }
            }
            val supported = am.getSupportedMixerAttributes(dev)
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            val match = supported.firstOrNull {
                it.format.sampleRate == sampleRate && it.format.encoding == enc &&
                    Integer.bitCount(it.format.channelMask) == channels
            }
            if (match == null) {
                am.clearPreferredMixerAttributes(attrs, dev)
                status = if (supported.isEmpty()) "Il DAC non dichiara la modalit\u00e0 bit-perfect"
                else "Il DAC non accetta ${sampleRate / 1000.0} kHz in questo formato (uso il mixer Android)"
                AppLog.i("Audio", "DAC: $status")
                return
            }
            val ok = am.setPreferredMixerAttributes(attrs, dev, match)
            status = if (ok) "Bit-perfect attivo: ${sampleRate / 1000.0} kHz diretti al DAC"
            else "Android ha rifiutato la modalit\u00e0 bit-perfect"
            AppLog.i("Audio", "DAC: $status")
        } catch (t: Throwable) {
            status = "Errore: ${t.message}"
            AppLog.w("Audio", "DAC: $status")
        }
    }
}
