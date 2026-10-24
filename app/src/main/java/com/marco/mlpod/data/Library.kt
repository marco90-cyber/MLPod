package com.marco.mlpod.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlpod.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Libreria musicale: legge le cartelle scelte dall'utente (Storage Access Framework),
 * estrae i metadati e le copertine, salva tutto in una cache JSON.
 */
class Library(private val context: Context) {

    var tracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var artists by mutableStateOf<List<Artist>>(emptyList())
        private set
    var genres by mutableStateOf<List<Genre>>(emptyList())
        private set

    /** Cambia a ogni aggiornamento della libreria. */
    var revision by mutableIntStateOf(0)
        private set

    var scan by mutableStateOf(ScanStatus())
        private set

    var lastScanInfo by mutableStateOf("")
        private set

    private val coversDir = File(context.filesDir, "covers")
    private val dbFile = File(context.filesDir, "library.json")

    fun coverFile(albumKey: String?): File? {
        if (albumKey == null) return null
        val f = File(coversDir, "$albumKey.jpg")
        return if (f.exists()) f else null
    }

    fun coverCount(): Int = coversDir.list()?.size ?: 0

    // ------------------------------------------------------------------ caricamento

    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            if (!dbFile.exists()) {
                AppLog.i("Libreria", "Nessuna cache: libreria vuota")
                return@withContext
            }
            val arr = JSONArray(dbFile.readText())
            val list = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) list.add(trackFromJson(arr.getJSONObject(i)))
            applyTracks(list)
            AppLog.i("Libreria", "Caricati ${list.size} brani dalla cache")
        } catch (e: Throwable) {
            AppLog.e("Libreria", "Errore nel caricamento della cache", e)
        }
    }

    private suspend fun applyTracks(list: List<Track>) {
        val grouped = withContext(Dispatchers.Default) { group(list) }
        tracks = grouped.tracks
        albums = grouped.albums
        artists = grouped.artists
        genres = grouped.genres
        revision++
    }

    private class Grouped(
        val tracks: List<Track>,
        val albums: List<Album>,
        val artists: List<Artist>,
        val genres: List<Genre>,
    )

    private fun group(list: List<Track>): Grouped {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val byTitle = Comparator<Track> { a, b -> collator.compare(a.title, b.title) }
        val sortedTracks = list.sortedWith(byTitle)

        val albumList = list.groupBy { it.albumKey }.map { (key, ts) ->
            val sorted = ts.sortedWith(
                compareBy<Track>({ it.discNo }, { it.trackNo }).then(byTitle)
            )
            val first = sorted.first()
            Album(
                key = key,
                title = first.album,
                artist = first.albumArtist.ifBlank { first.artist },
                tracks = sorted,
            )
        }.sortedWith { a, b -> collator.compare(a.title, b.title) }

        val artistList = albumList.groupBy { it.artist }.map { (name, albs) ->
            Artist(
                name = name,
                albums = albs.sortedWith(
                    compareBy<Album>({ a -> a.tracks.minOfOrNull { t -> if (t.year > 0) t.year else 9999 } ?: 9999 })
                        .then { a, b -> collator.compare(a.title, b.title) }
                )
            )
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }

        val genreList = list.groupBy { it.genre }.map { (name, ts) ->
            Genre(name, ts.sortedWith(byTitle))
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }

        return Grouped(sortedTracks, albumList, artistList, genreList)
    }

    // ------------------------------------------------------------------ scansione

    private class AudioFile(
        val uri: Uri,
        val name: String,
        val ext: String,
        val modified: Long,
        val size: Long,
        val dirId: String,
    )

    suspend fun rescan(folders: List<String>, rebuild: Boolean) = withContext(Dispatchers.IO) {
        if (scan.running) return@withContext
        scan = ScanStatus(running = true, message = "Ricerca dei file...")
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i("Libreria", "Scansione avviata (${folders.size} cartelle, ricostruzione=$rebuild)")
        try {
            if (folders.isEmpty()) {
                applyTracks(emptyList())
                saveDb(emptyList())
                AppLog.w("Libreria", "Nessuna cartella selezionata")
                scan = ScanStatus(running = false, message = "Nessuna cartella")
                lastScanInfo = "Nessuna cartella selezionata"
                return@withContext
            }
            coversDir.mkdirs()
            if (rebuild) {
                coversDir.listFiles()?.forEach { it.delete() }
            }
            val old: Map<String, Track> =
                if (rebuild) emptyMap() else tracks.associateBy { it.uri }

            val files = ArrayList<AudioFile>()
            val folderCovers = HashMap<String, Uri>()
            for (f in folders) {
                try {
                    walk(Uri.parse(f), files, folderCovers)
                } catch (e: Throwable) {
                    AppLog.e("Libreria", "Errore nella lettura della cartella $f", e)
                }
            }
            AppLog.i("Libreria", "Trovati ${files.size} file audio")
            scan = ScanStatus(running = true, done = 0, total = files.size, message = "Lettura dei metadati...")

            val coverKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
            coversDir.list()?.forEach { coverKeys.add(it.removeSuffix(".jpg")) }

            val results = arrayOfNulls<Track>(files.size)
            val counter = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val reused = AtomicInteger(0)
            val gate = Semaphore(4)

            coroutineScope {
                files.mapIndexed { i, f ->
                    async {
                        gate.withPermit {
                            val prev = old[f.uri.toString()]
                            val t: Track? = if (prev != null && prev.modified == f.modified && prev.size == f.size) {
                                reused.incrementAndGet()
                                prev
                            } else {
                                try {
                                    readTrack(f, folderCovers, coverKeys)
                                } catch (e: Throwable) {
                                    failed.incrementAndGet()
                                    AppLog.w("Libreria", "Impossibile leggere ${f.name}", e)
                                    null
                                }
                            }
                            results[i] = t
                            val n = counter.incrementAndGet()
                            if (n % 10 == 0 || n == files.size) {
                                scan = scan.copy(done = n)
                            }
                        }
                    }
                }.awaitAll()
            }

            val list = results.filterNotNull()
            applyTracks(list)
            saveDb(list)
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val info = "${list.size} brani, ${albums.size} album, ${artists.size} artisti " +
                "(${reused.get()} riutilizzati, ${failed.get()} errori) in ${String.format("%.1f", secs)} s"
            lastScanInfo = info
            AppLog.i("Libreria", "Scansione completata: $info")
            scan = ScanStatus(running = false, done = list.size, total = files.size, message = "Completata")
        } catch (e: Throwable) {
            AppLog.e("Libreria", "Scansione fallita", e)
            scan = ScanStatus(running = false, message = "Errore")
            lastScanInfo = "Errore durante la scansione"
        }
    }

    private fun walk(tree: Uri, files: MutableList<AudioFile>, covers: MutableMap<String, Uri>) {
        val resolver = context.contentResolver
        val stack = ArrayList<String>()
        stack.add(DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (stack.isNotEmpty()) {
            val dir = stack.removeAt(stack.lastIndex)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
            val cursor = try {
                resolver.query(children, projection, null, null, null)
            } catch (e: Throwable) {
                AppLog.w("Libreria", "Cartella non leggibile: $dir", e)
                null
            } ?: continue
            cursor.use { c ->
                var coverScore = 0
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.add(id)
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext in AUDIO_EXT) {
                        files.add(
                            AudioFile(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                name = name,
                                ext = ext,
                                modified = if (c.isNull(3)) 0L else c.getLong(3),
                                size = if (c.isNull(4)) 0L else c.getLong(4),
                                dirId = dir,
                            )
                        )
                    } else if (ext in IMAGE_EXT) {
                        val score = coverNameScore(name)
                        if (score > coverScore) {
                            coverScore = score
                            covers[dir] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        }
                    }
                }
            }
        }
    }

    private fun coverNameScore(name: String): Int {
        val base = name.substringBeforeLast('.').lowercase(Locale.ROOT)
        return when {
            base == "cover" -> 6
            base == "folder" -> 5
            base == "front" -> 4
            base == "album" || base == "albumart" -> 3
            base.startsWith("albumart") -> 2
            base.contains("cover") || base.contains("front") -> 1
            else -> 0
        }
    }

    private fun readTrack(f: AudioFile, folderCovers: Map<String, Uri>, coverKeys: MutableSet<String>): Track {
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var genre: String? = null
        var duration = 0L
        var trackNo = 0
        var discNo = 0
        var year = 0
        var bitrate = 0
        var sampleRate = 0
        var bits = 0
        var embedded: ByteArray? = null

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, f.uri)
            fun m(key: Int): String? = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            title = m(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            albumArtist = m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE)
            duration = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            trackNo = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            discNo = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            year = leadingInt(m(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: m(MediaMetadataRetriever.METADATA_KEY_DATE))
            bitrate = (m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sampleRate = m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                bits = m(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            }
        } finally {
            try {
                r.release()
            } catch (_: Throwable) {
            }
        }

        // Frequenza di campionamento / profondità: MediaExtractor e intestazioni dirette.
        if (sampleRate <= 0 || bits <= 0) {
            try {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(context, f.uri, null)
                    for (i in 0 until ex.trackCount) {
                        val fmt = ex.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("audio/")) continue
                        if (sampleRate <= 0 && fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (bits <= 0 && fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            bits = when (fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                                android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                                android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                                android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
                                android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                                else -> 0
                            }
                        }
                        break
                    }
                } finally {
                    ex.release()
                }
            } catch (_: Throwable) {
            }
        }
        if (f.ext == "flac" || f.ext == "wav") {
            val h = readLosslessHeader(f)
            if (h != null) {
                if (h.first > 0) sampleRate = h.first
                if (h.second > 0) bits = h.second
            }
        }

        val displayName = f.name.substringBeforeLast('.')
        val finalTitle = title ?: displayName
        val finalArtist = artist ?: albumArtist ?: "Artista sconosciuto"
        val finalAlbum = album ?: ""
        val keyBase = if (finalAlbum.isNotEmpty()) {
            "${(albumArtist ?: finalArtist).lowercase(Locale.ROOT)}|${finalAlbum.lowercase(Locale.ROOT)}"
        } else {
            "dir:${f.dirId}"
        }
        val albumKey = md5(keyBase)

        // Copertina: una sola volta per album.
        if (coverKeys.add(albumKey)) {
            try {
                val rr = MediaMetadataRetriever()
                try {
                    rr.setDataSource(context, f.uri)
                    embedded = rr.embeddedPicture
                } finally {
                    try {
                        rr.release()
                    } catch (_: Throwable) {
                    }
                }
                if (embedded == null) {
                    val cover = folderCovers[f.dirId]
                    if (cover != null) {
                        embedded = context.contentResolver.openInputStream(cover)?.use { it.readBytes() }
                    }
                }
                val bytes = embedded
                if (bytes != null) saveCover(albumKey, bytes)
            } catch (e: Throwable) {
                AppLog.w("Libreria", "Copertina non salvata per ${f.name}", e)
            }
        }

        return Track(
            id = md5(f.uri.toString()).take(16),
            uri = f.uri.toString(),
            title = finalTitle,
            artist = finalArtist,
            album = if (finalAlbum.isEmpty()) "Album sconosciuto" else finalAlbum,
            albumArtist = albumArtist ?: "",
            genre = genre ?: "Sconosciuto",
            durationMs = duration,
            trackNo = trackNo,
            discNo = discNo,
            year = year,
            sampleRate = sampleRate,
            bits = bits,
            bitrateKbps = bitrate,
            ext = f.ext,
            size = f.size,
            modified = f.modified,
            albumKey = albumKey,
        )
    }

    /** Legge frequenza e profondità in bit dall'intestazione di FLAC e WAV. */
    private fun readLosslessHeader(f: AudioFile): Pair<Int, Int>? {
        return try {
            context.contentResolver.openInputStream(f.uri)?.use { input ->
                val buf = ByteArray(128)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (f.ext == "flac") {
                    // "fLaC" + intestazione blocco (4 byte) + STREAMINFO
                    if (read >= 26 && buf[0] == 'f'.code.toByte() && buf[1] == 'L'.code.toByte()) {
                        val o = 8 + 10
                        val b0 = buf[o].toInt() and 0xFF
                        val b1 = buf[o + 1].toInt() and 0xFF
                        val b2 = buf[o + 2].toInt() and 0xFF
                        val b3 = buf[o + 3].toInt() and 0xFF
                        val rate = (b0 shl 12) or (b1 shl 4) or (b2 shr 4)
                        val bps = (((b2 and 1) shl 4) or (b3 shr 4)) + 1
                        Pair(rate, bps)
                    } else null
                } else {
                    // WAV: cerca il chunk "fmt "
                    var p = 12
                    var result: Pair<Int, Int>? = null
                    while (p + 8 <= read) {
                        val id = String(buf, p, 4, Charsets.ISO_8859_1)
                        val size = le32(buf, p + 4)
                        if (id == "fmt " && p + 8 + 16 <= read) {
                            val rate = le32(buf, p + 12)
                            val bps = (buf[p + 22].toInt() and 0xFF) or ((buf[p + 23].toInt() and 0xFF) shl 8)
                            result = Pair(rate, bps)
                            break
                        }
                        if (size < 0) break
                        p += 8 + size + (size and 1)
                    }
                    result
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun saveCover(key: String, bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_COVER && bounds.outHeight / (sample * 2) >= MAX_COVER) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return
        val scaled = if (bmp.width > MAX_COVER || bmp.height > MAX_COVER) {
            val ratio = MAX_COVER.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt().coerceAtLeast(1), (bmp.height * ratio).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        coversDir.mkdirs()
        val tmp = File(coversDir, "$key.tmp")
        tmp.writeBytes(out.toByteArray())
        tmp.renameTo(File(coversDir, "$key.jpg"))
    }

    // ------------------------------------------------------------------ cache JSON

    private fun saveDb(list: List<Track>) {
        try {
            val arr = JSONArray()
            for (t in list) arr.put(trackToJson(t))
            val tmp = File(context.filesDir, "library.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(dbFile)
        } catch (e: Throwable) {
            AppLog.e("Libreria", "Salvataggio cache fallito", e)
        }
    }

    private fun trackToJson(t: Track) = JSONObject().apply {
        put("id", t.id); put("uri", t.uri); put("title", t.title); put("artist", t.artist)
        put("album", t.album); put("albumArtist", t.albumArtist); put("genre", t.genre)
        put("dur", t.durationMs); put("tn", t.trackNo); put("dn", t.discNo); put("year", t.year)
        put("sr", t.sampleRate); put("bits", t.bits); put("br", t.bitrateKbps); put("ext", t.ext)
        put("size", t.size); put("mod", t.modified); put("ak", t.albumKey)
    }

    private fun trackFromJson(o: JSONObject) = Track(
        id = o.getString("id"), uri = o.getString("uri"), title = o.getString("title"),
        artist = o.getString("artist"), album = o.getString("album"),
        albumArtist = o.optString("albumArtist", ""), genre = o.optString("genre", "Sconosciuto"),
        durationMs = o.optLong("dur", 0), trackNo = o.optInt("tn", 0), discNo = o.optInt("dn", 0),
        year = o.optInt("year", 0), sampleRate = o.optInt("sr", 0), bits = o.optInt("bits", 0),
        bitrateKbps = o.optInt("br", 0), ext = o.optString("ext", ""), size = o.optLong("size", 0),
        modified = o.optLong("mod", 0), albumKey = o.getString("ak"),
    )

    // ------------------------------------------------------------------ utilità

    private fun leadingInt(s: String?): Int {
        if (s == null) return 0
        return Regex("^\\d+").find(s.trim())?.value?.toIntOrNull() ?: 0
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_COVER = 640

        /** Formati principali: MP3, FLAC, WAV, OGG/Opus, AAC/M4A, MKA, AMR. */
        val AUDIO_EXT = setOf(
            "mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "m4b", "aac", "mka", "amr", "3gp"
        )
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
    }
}
