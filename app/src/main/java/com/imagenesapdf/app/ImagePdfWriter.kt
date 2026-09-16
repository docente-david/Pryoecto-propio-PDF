package com.imagenesapdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Escribe un PDF con una imagen por página, directamente sobre el flujo de salida.
 *
 * Se genera el PDF "a mano" (en vez de usar android.graphics.pdf.PdfDocument) para poder
 * incrustar cada imagen como JPEG con /DCTDecode: así la calidad elegida por el usuario
 * controla de verdad el peso del archivo, y no hace falta tener todas las páginas en memoria.
 */
class ImagePdfWriter(private val context: Context) {

    class ImageReadException(val index: Int, cause: Throwable? = null) :
        IOException("No se pudo leer la imagen ${index + 1}", cause)

    private class JpegImage(val bytes: ByteArray, val width: Int, val height: Int)

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /**
     * @param onProgress recibe (imágenes procesadas, total). Se invoca desde un hilo de fondo.
     */
    suspend fun write(
        uris: List<Uri>,
        options: PdfOptions,
        output: OutputStream,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val n = uris.size
        require(n > 0) { "Sin imágenes" }

        val out = CountingOutputStream(BufferedOutputStream(output, 64 * 1024))
        // Objetos: 1 catálogo, 2 árbol de páginas, 3 info; por cada página i: 4+3i página, 5+3i contenido, 6+3i imagen.
        val totalObjects = 3 + 3 * n
        val offsets = LongArray(totalObjects + 1)

        fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun beginObj(num: Int) { offsets[num] = out.count; ascii("$num 0 obj\n") }
        fun endObj() = ascii("endobj\n")

        ascii("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")

        beginObj(1); ascii("<< /Type /Catalog /Pages 2 0 R >>\n"); endObj()

        beginObj(2)
        val kids = (0 until n).joinToString(" ") { "${4 + 3 * it} 0 R" }
        ascii("<< /Type /Pages /Kids [$kids] /Count $n >>\n")
        endObj()

        beginObj(3)
        val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        ascii("<< /Producer (Imagenes a PDF) /Title (${pdfString(options.fileName)}) /CreationDate (D:$date) >>\n")
        endObj()

        uris.forEachIndexed { i, uri ->
            ensureActive()
            onProgress(i, n)

            val jpeg = try {
                encodeImage(uri, options.quality)
            } catch (e: Exception) {
                throw ImageReadException(i, e)
            }

            val (pageW, pageH, margin) = pageGeometry(options, jpeg.width, jpeg.height)
            val availW = pageW - 2 * margin
            val availH = pageH - 2 * margin
            val scale = min(availW / jpeg.width, availH / jpeg.height)
            val drawW = jpeg.width * scale
            val drawH = jpeg.height * scale
            val x = (pageW - drawW) / 2
            val y = (pageH - drawH) / 2

            val pageObj = 4 + 3 * i
            val contentObj = pageObj + 1
            val imageObj = pageObj + 2

            beginObj(pageObj)
            ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pageW)} ${fmt(pageH)}] " +
                    "/Resources << /XObject << /Im$i $imageObj 0 R >> >> /Contents $contentObj 0 R >>\n"
            )
            endObj()

            val content = "q ${fmt(drawW)} 0 0 ${fmt(drawH)} ${fmt(x)} ${fmt(y)} cm /Im$i Do Q\n"
            beginObj(contentObj)
            ascii("<< /Length ${content.length} >>\nstream\n")
            ascii(content)
            ascii("endstream\n")
            endObj()

            beginObj(imageObj)
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${jpeg.width} /Height ${jpeg.height} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.bytes.size} >>\nstream\n"
            )
            out.write(jpeg.bytes)
            ascii("\nendstream\n")
            endObj()
        }
        onProgress(n, n)

        val xrefPos = out.count
        ascii("xref\n0 ${totalObjects + 1}\n")
        ascii("0000000000 65535 f \n")
        for (obj in 1..totalObjects) ascii(String.format(Locale.US, "%010d 00000 n \n", offsets[obj]))
        ascii("trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        out.flush()
    }

    /** Devuelve (ancho, alto, margen) de la página para una imagen de imgW×imgH píxeles. */
    private fun pageGeometry(o: PdfOptions, imgW: Int, imgH: Int): Triple<Float, Float, Float> {
        if (o.pageSize == PageSize.FIT) {
            // Página con la proporción de la imagen; el lado mayor mide lo mismo que el alto de un A4.
            val longSide = 842f
            val s = longSide / max(imgW, imgH)
            return Triple(imgW * s, imgH * s, 0f)
        }
        val landscape = when (o.orientation) {
            Orientation.PORTRAIT -> false
            Orientation.LANDSCAPE -> true
            Orientation.AUTO -> imgW > imgH
        }
        val w = if (landscape) o.pageSize.heightPt else o.pageSize.widthPt
        val h = if (landscape) o.pageSize.widthPt else o.pageSize.heightPt
        return Triple(w, h, o.marginPt)
    }

    /** Decodifica la imagen (con submuestreo), corrige la rotación EXIF, aplana transparencias y la comprime a JPEG. */
    private fun encodeImage(uri: Uri, quality: Int): JpegImage {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("No se pudo abrir la imagen")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Formato de imagen no reconocido")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("No se pudo decodificar la imagen")

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
        }

        if (bitmap.hasAlpha()) {
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            bitmap.recycle()
            bitmap = flat
        }

        val bos = ByteArrayOutputStream(bitmap.byteCount / 8)
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)) {
            bitmap.recycle()
            throw IOException("No se pudo comprimir la imagen")
        }
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()
        return JpegImage(bos.toByteArray(), w, h)
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    /** Escapa una cadena para un literal PDF entre paréntesis. */
    private fun pdfString(s: String): String =
        s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("\r", " ").replace("\n", " ")

    companion object {
        /** Lado mayor máximo tras el submuestreo: suficiente para imprimir A4 a ~300 dpi. */
        private const val MAX_SIDE_PX = 3000
    }
}
