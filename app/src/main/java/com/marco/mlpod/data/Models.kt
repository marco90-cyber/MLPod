package com.marco.mlpod.data

data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val durationMs: Long,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val sampleRate: Int,
    val bits: Int,
    val bitrateKbps: Int,
    val ext: String,
    val size: Long,
    val modified: Long,
    val albumKey: String,
) {
    /** Hi-Res: oltre 48 kHz oppure più di 16 bit. */
    val isHiResFile: Boolean get() = sampleRate > 48000 || bits > 16

    val formatLabel: String get() = ext.uppercase()

    fun detailLine(): String {
        val parts = ArrayList<String>()
        parts.add(formatLabel)
        if (sampleRate > 0) {
            val khz = sampleRate / 1000.0
            parts.add(if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz" else String.format("%.1f kHz", khz))
        }
        if (bits > 0) parts.add("$bits bit")
        if (bitrateKbps > 0) parts.add("$bitrateKbps kbps")
        return parts.joinToString(" · ")
    }
}

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val tracks: List<Track>,
)

data class Artist(
    val name: String,
    val albums: List<Album>,
) {
    val tracks: List<Track> get() = albums.flatMap { it.tracks }
}

data class Genre(
    val name: String,
    val tracks: List<Track>,
)

data class ScanStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
)
