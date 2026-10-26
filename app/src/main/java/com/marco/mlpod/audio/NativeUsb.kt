package com.marco.mlpod.audio

import java.nio.ByteBuffer

/** Ponte JNI verso l'uscita USB isocrona diretta (stereo, 16 bit). */
object NativeUsb {
    val available: Boolean get() = NativeDsp.available

    external fun nOpen(fd: Int, ep: Int, rate: Int, intervalUs: Int, maxPacket: Int): Long
    external fun nClose(h: Long)
    external fun nWrite(h: Long, buf: ByteBuffer, off: Int, len: Int): Int
    external fun nPlayed(h: Long): Long
    external fun nBuffered(h: Long): Long
    external fun nSetPaused(h: Long, paused: Boolean)
    external fun nSetGain(h: Long, gain: Float)
    external fun nFlush(h: Long)
    external fun nFailed(h: Long): Boolean
    external fun nErrno(h: Long): Int
    external fun nRestart(h: Long)
}
