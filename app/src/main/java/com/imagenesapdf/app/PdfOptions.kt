package com.imagenesapdf.app

/** Tamaños de página en puntos PDF (1 pt = 1/72 in). FIT usa la proporción de cada imagen. */
enum class PageSize(val widthPt: Float, val heightPt: Float) {
    A4(595f, 842f),
    LETTER(612f, 792f),
    FIT(0f, 0f)
}

enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

data class PdfOptions(
    /** Nombre sin extensión; se limpia y se le agrega .pdf al guardar. */
    val fileName: String,
    val pageSize: PageSize,
    val orientation: Orientation,
    /** Calidad JPEG 1–100. */
    val quality: Int,
    /** Margen alrededor de la imagen en A4/Carta. */
    val marginPt: Float = 24f
)
