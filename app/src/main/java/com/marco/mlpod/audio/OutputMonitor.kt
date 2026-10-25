package com.marco.mlpod.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class OutKind(val label: String) {
    Speaker("Altoparlante del telefono"),
    Wired("Cuffie / jack"),
    Bluetooth("Bluetooth"),
    Usb("DAC / audio USB"),
    Hdmi("HDMI"),
    Other("Altra uscita"),
}

/** Segue l'uscita audio attiva (altoparlante, Bluetooth, USB DAC...). */
class OutputMonitor(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var kind by mutableStateOf(OutKind.Speaker)
        private set
    var name by mutableStateOf("")
        private set

    /** true se il dispositivo è quello realmente instradato (Android 13+), false se stimato. */
    var exact by mutableStateOf(false)
        private set

    init {
        refresh()
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }, null)
    }

    fun refresh() {
        var dev: AudioDeviceInfo? = null
        var ex = false
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                dev = am.getAudioDevicesForAttributes(attrs).firstOrNull()
                ex = dev != null
            } catch (_: Throwable) {
            }
        }
        if (dev == null) {
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            dev = outs.minByOrNull { priority(kindOf(it.type)) }
        }
        kind = dev?.let { kindOf(it.type) } ?: OutKind.Speaker
        name = dev?.productName?.toString().orEmpty()
        exact = ex
    }

    private fun priority(k: OutKind) = when (k) {
        OutKind.Usb -> 0
        OutKind.Bluetooth -> 1
        OutKind.Wired -> 2
        OutKind.Hdmi -> 3
        OutKind.Other -> 4
        OutKind.Speaker -> 5
    }

    companion object {
        fun kindOf(type: Int): OutKind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutKind.Speaker
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> OutKind.Wired
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID -> OutKind.Bluetooth
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutKind.Usb
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> OutKind.Hdmi
            else -> OutKind.Other
        }
    }
}
