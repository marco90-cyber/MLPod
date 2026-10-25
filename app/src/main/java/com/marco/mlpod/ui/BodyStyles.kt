package com.marco.mlpod.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Colore della scocca (e di conseguenza della ghiera). */
class BodyStyle(
    val id: String,
    val name: String,
    val top: Color,
    val bottom: Color,
    val wheel: Color,
    val wheelLabel: Color,
    val center: Color,
) {
    /** Ghiera scura per la modalità notte (la scocca resta quella scelta). */
    fun nightWheel() = BodyStyle(
        id, name, top, bottom,
        wheel = Color(0xFF1F2024), wheelLabel = Color(0xFFB9BCC4), center = Color(0xFF131417),
    )
}

object BodyStyles {
    private fun colored(id: String, name: String, hex: Long): BodyStyle {
        val base = Color(hex)
        return BodyStyle(
            id = id,
            name = name,
            top = lerp(base, Color.White, 0.12f),
            bottom = lerp(base, Color.Black, 0.18f),
            wheel = lerp(base, Color.White, 0.72f),
            wheelLabel = lerp(base, Color.Black, 0.45f),
            center = lerp(base, Color.White, 0.84f),
        )
    }

    val all: List<BodyStyle> = listOf(
        BodyStyle(
            "argento", "Argento", Color(0xFFDEDFE3), Color(0xFFBDBFC5),
            Color(0xFFF3F3F5), Color(0xFF8C8E95), Color(0xFFF8F8FA)
        ),
        BodyStyle(
            "nero", "Nero", Color(0xFF2B2B2E), Color(0xFF0E0E10),
            Color(0xFF2F2F33), Color(0xFFA6A7AD), Color(0xFF1B1B1E)
        ),
        BodyStyle(
            "bianco", "Bianco", Color(0xFFFAFAFC), Color(0xFFE0E0E5),
            Color(0xFFF0F0F3), Color(0xFF9A9BA2), Color(0xFFFDFDFE)
        ),
        BodyStyle(
            "grafite", "Grafite", Color(0xFF63666E), Color(0xFF3B3D43),
            Color(0xFF6E7179), Color(0xFFD0D1D6), Color(0xFF55585F)
        ),
        colored("blu", "Blu", 0xFF2F6FD6),
        colored("azzurro", "Azzurro", 0xFF4FB3E8),
        colored("turchese", "Turchese", 0xFF1FB5A8),
        colored("verde", "Verde", 0xFF4CAF50),
        colored("lime", "Lime", 0xFF9BCB2E),
        colored("giallo", "Giallo", 0xFFF2C81E),
        colored("arancione", "Arancione", 0xFFF28A25),
        colored("rosso", "Rosso", 0xFFD93636),
        colored("bordeaux", "Bordeaux", 0xFF8C1F3B),
        colored("rosa", "Rosa", 0xFFEE6AA7),
        colored("viola", "Viola", 0xFF8656D6),
        colored("oro", "Oro", 0xFFD1AD72),
        colored("rosa-oro", "Rosa oro", 0xFFE3A99B),
    )

    fun byId(id: String): BodyStyle = all.firstOrNull { it.id == id } ?: all.first()
}
