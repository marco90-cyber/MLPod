package com.marco.mlpod.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Caricamento copertine dalla cache su disco, con cache in memoria. */
object Covers {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun keyOf(file: File, maxPx: Int) = "${file.path}@$maxPx"

    fun cached(file: File, maxPx: Int): Bitmap? = cache.get(keyOf(file, maxPx))

    fun load(file: File, maxPx: Int): Bitmap? {
        val key = keyOf(file, maxPx)
        val hit = cache.get(key)
        if (hit != null) return hit
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.path, opts)
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}

/** Restituisce la copertina (o null se non presente) senza bloccare la UI. */
@Composable
fun rememberCover(file: File?, maxPx: Int): ImageBitmap? {
    val initial = if (file != null) Covers.cached(file, maxPx)?.asImageBitmap() else null
    val state = produceState<ImageBitmap?>(initialValue = initial, file?.path, maxPx) {
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) { Covers.load(file, maxPx)?.asImageBitmap() }
        }
    }
    return state.value
}
