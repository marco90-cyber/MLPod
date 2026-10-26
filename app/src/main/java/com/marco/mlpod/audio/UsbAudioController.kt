package com.marco.mlpod.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.marco.mlpod.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Uscita USB esclusiva: l'app parla direttamente col DAC (USB Audio Class 1, stereo 16 bit)
 * senza passare dal mixer di Android. Niente ricampionamento: la frequenza del file deve essere
 * una di quelle dichiarate dal DAC.
 */
class UsbAudioController(private val ctx: Context) {
    @Volatile
    var enabled: Boolean = false

    var status by mutableStateOf("Spenta")
        private set

    private class Alt(
        val iface: Int, val alt: Int, val channels: Int, val subframe: Int, val bits: Int,
        val rates: List<Int>, val rangeMin: Int, val rangeMax: Int,
        val ep: Int, val maxPacket: Int, val interval: Int, val freqControl: Boolean,
    ) {
        fun supports(rate: Int) = rate in rates || (rangeMax > 0 && rate in rangeMin..rangeMax)
    }

    private val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice? = null
    private var conn: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var handle = 0L
    private var rate = 0
    private var scratch: ByteBuffer? = null
    private var parsed: List<Alt>? = null
    private var uacVersion = 0

    init {
        ContextCompat.registerReceiver(
            ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        ACTION_PERMISSION -> {
                            val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            status = if (ok) "Permesso concesso: parte dal prossimo brano" else "Permesso USB negato"
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            closeOutput()
                            parsed = null
                            device = null
                            if (enabled) status = "DAC scollegato"
                        }
                    }
                }
            },
            IntentFilter(ACTION_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun findDevice(): UsbDevice? = um.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any {
            val i = d.getInterface(it)
            i.interfaceClass == UsbConstants.USB_CLASS_AUDIO && i.interfaceSubclass == 2
        }
    }

    /** Da chiamare quando l'utente attiva l'opzione. */
    fun requestAccess() {
        val d = findDevice()
        if (d == null) {
            status = "Nessun DAC USB collegato"
            return
        }
        device = d
        if (um.hasPermission(d)) {
            status = "Pronta (permesso già concesso)"
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
        um.requestPermission(d, pi)
        status = "In attesa del permesso USB"
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)

    private fun parse(raw: ByteArray): List<Alt> {
        val out = ArrayList<Alt>()
        var pos = 0
        var curIface = -1
        var curAlt = 0
        var inStream = false
        var ch = 0; var sub = 0; var bits = 0
        var rates = emptyList<Int>(); var rMin = 0; var rMax = 0
        var ep = -1; var maxP = 0; var itv = 1; var freqCtl = false
        var haveFmt = false
        fun flush() {
            if (inStream && haveFmt && ep >= 0) {
                out.add(Alt(curIface, curAlt, ch, sub, bits, rates, rMin, rMax, ep, maxP, itv, freqCtl))
            }
        }
        while (pos + 2 <= raw.size) {
            val len = u8(raw, pos)
            if (len < 2 || pos + len > raw.size) break
            val type = u8(raw, pos + 1)
            when (type) {
                0x04 -> { // interfaccia
                    flush()
                    curIface = u8(raw, pos + 2)
                    curAlt = u8(raw, pos + 3)
                    val cls = u8(raw, pos + 5)
                    val sc = u8(raw, pos + 6)
                    inStream = cls == 1 && sc == 2
                    haveFmt = false; ep = -1; freqCtl = false; rates = emptyList(); rMin = 0; rMax = 0
                }
                0x24 -> { // CS_INTERFACE
                    val sub0 = if (len > 2) u8(raw, pos + 2) else -1
                    if (sub0 == 0x01 && !inStream && len >= 5) uacVersion = u16(raw, pos + 3) // header controllo
                    if (inStream && sub0 == 0x02 && len >= 8 && u8(raw, pos + 3) == 1) { // FORMAT_TYPE I (UAC1)
                        ch = u8(raw, pos + 4); sub = u8(raw, pos + 5); bits = u8(raw, pos + 6)
                        val n = u8(raw, pos + 7)
                        if (n == 0 && len >= 14) {
                            rMin = u24(raw, pos + 8); rMax = u24(raw, pos + 11); rates = emptyList()
                        } else {
                            val l = ArrayList<Int>()
                            for (k in 0 until n) if (pos + 8 + k * 3 + 3 <= pos + len) l.add(u24(raw, pos + 8 + k * 3))
                            rates = l
                        }
                        haveFmt = true
                    }
                }
                0x05 -> { // endpoint
                    if (inStream && len >= 7) {
                        val addr = u8(raw, pos + 2)
                        val attr = u8(raw, pos + 3)
                        if ((addr and 0x80) == 0 && (attr and 0x03) == 1) {
                            ep = addr; maxP = u16(raw, pos + 4); itv = u8(raw, pos + 6)
                        }
                    }
                }
                0x25 -> { // CS_ENDPOINT
                    if (inStream && len >= 4) freqCtl = (u8(raw, pos + 3) and 0x01) != 0
                }
            }
            pos += len
        }
        flush()
        return out
    }

    private fun chooseAlt(list: List<Alt>, r: Int): Alt? =
        list.firstOrNull { it.channels == 2 && it.subframe == 2 && it.bits == 16 && it.supports(r) }

    /** true se la riproduzione a [r] Hz può andare diretta al DAC (stereo 16 bit). */
    fun canUse(r: Int): Boolean {
        if (!enabled) return false
        if (!NativeUsb.available) { status = "Libreria nativa non disponibile"; return false }
        val d = device ?: findDevice().also { device = it }
        if (d == null) { status = "Nessun DAC USB collegato"; return false }
        if (!um.hasPermission(d)) { status = "Serve il permesso USB (riattiva l'opzione)"; return false }
        var list = parsed
        if (list == null) {
            val c = um.openDevice(d)
            if (c == null) { status = "Impossibile aprire il DAC"; return false }
            val raw = c.rawDescriptors
            c.close()
            list = parse(raw)
            parsed = list
            AppLog.i("Audio", "USB: UAC ${"%04x".format(uacVersion)}, formati: " + list.joinToString { "if${it.iface}/alt${it.alt} ${it.channels}ch ${it.bits}bit ${it.rates}" })
        }
        if (uacVersion >= 0x0200) { status = "DAC UAC2: non ancora supportato (uso Android)"; return false }
        if (list.isEmpty()) { status = "Descrittori audio non riconosciuti (uso Android)"; return false }
        if (chooseAlt(list, r) == null) {
            val all = list.flatMap { it.rates }.distinct().sorted()
            status = "Il DAC non accetta ${r / 1000.0} kHz stereo 16 bit (accetta: " +
                all.joinToString { "${it / 1000.0}" } + " kHz) · uso Android"
            return false
        }
        return true
    }

    fun open(r: Int): Boolean {
        closeOutput()
        val d = device ?: return false
        val a = chooseAlt(parsed ?: return false, r) ?: return false
        val c = um.openDevice(d) ?: return false
        var target: UsbInterface? = null
        for (k in 0 until d.interfaceCount) {
            val i = d.getInterface(k)
            if (i.id == a.iface && i.alternateSetting == a.alt) target = i
        }
        if (target == null || !c.claimInterface(target, true) || !c.setInterface(target)) {
            c.close(); status = "Il DAC non si lascia configurare (uso Android)"; return false
        }
        if (a.freqControl || a.rates.size > 1) {
            val b = byteArrayOf((r and 0xFF).toByte(), ((r shr 8) and 0xFF).toByte(), ((r shr 16) and 0xFF).toByte())
            val n = c.controlTransfer(0x22, 0x01, 0x0100, a.ep, b, 3, 500)
            AppLog.i("Audio", "USB: imposto $r Hz sull'endpoint -> $n")
        }
        val intervalUs = 1000 shl (a.interval - 1).coerceIn(0, 7)
        val h = NativeUsb.nOpen(c.fileDescriptor, a.ep, r, intervalUs, a.maxPacket)
        if (h == 0L) {
            AppLog.w("Audio", "USB: nOpen fallita")
            c.releaseInterface(target); c.close(); status = "Apertura USB fallita (uso Android)"; return false
        }
        conn = c; claimed = target; handle = h; rate = r
        status = "USB esclusivo attivo: ${r / 1000.0} kHz · 16 bit · stereo"
        AppLog.i("Audio", "USB: $status (ep=0x${a.ep.toString(16)}, intervallo ${intervalUs} µs, pacchetto max ${a.maxPacket})")
        return true
    }

    fun closeOutput() {
        val h = handle
        handle = 0L
        if (h != 0L) NativeUsb.nClose(h)
        val c = conn
        val i = claimed
        conn = null; claimed = null
        if (c != null) {
            try {
                val d = device
                if (d != null && i != null) {
                    for (k in 0 until d.interfaceCount) {
                        val x = d.getInterface(k)
                        if (x.id == i.id && x.alternateSetting == 0) c.setInterface(x)
                    }
                    c.releaseInterface(i)
                }
            } catch (_: Throwable) {
            }
            c.close()
        }
    }

    val isOpen: Boolean get() = handle != 0L && !NativeUsb.nFailed(handle)

    /** Scrive PCM16 stereo; consuma da [buf] quello che entra. */
    fun write(buf: ByteBuffer) {
        val h = handle
        if (h != 0L && NativeUsb.nFailed(h)) {
            val e = NativeUsb.nErrno(h)
            closeOutput()
            status = "Trasferimento USB interrotto (errore $e)"
            AppLog.w("Audio", "USB: $status")
        }
        if (handle == 0L) {
            // uscita chiusa: scarta i dati per non bloccare il player
            buf.position(buf.limit())
            return
        }
        if (!buf.hasRemaining()) return
        val n = buf.remaining()
        val used: Int
        if (buf.isDirect) {
            used = NativeUsb.nWrite(h, buf, buf.position(), n)
        } else {
            var s = scratch
            if (s == null || s.capacity() < n) {
                s = ByteBuffer.allocateDirect(maxOf(n, 65536)).order(ByteOrder.nativeOrder())
                scratch = s
            }
            s.clear()
            val dup = buf.duplicate()
            s.put(dup)
            used = NativeUsb.nWrite(h, s, 0, n)
        }
        buf.position(buf.position() + used)
    }

    fun playedFrames(): Long = if (handle != 0L) NativeUsb.nPlayed(handle) else 0L
    fun bufferedFrames(): Long = if (handle != 0L) NativeUsb.nBuffered(handle) else 0L
    fun setPaused(p: Boolean) { if (handle != 0L) NativeUsb.nSetPaused(handle, p) }
    fun setGain(g: Float) { if (handle != 0L) NativeUsb.nSetGain(handle, g) }
    fun flush() { if (handle != 0L) NativeUsb.nFlush(handle) }
    val sampleRate: Int get() = rate

    companion object {
        const val ACTION_PERMISSION = "com.marco.mlpod.USB_PERMISSION"
    }
}
