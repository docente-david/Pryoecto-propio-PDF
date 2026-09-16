package com.imagenesapdf.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Crea el archivo de salida en Descargas / Imagenes a PDF.
 * En Android 10+ usa MediaStore (sin permisos); en Android 8–9 escribe el archivo directamente.
 */
object PdfSaver {

    const val FOLDER = "Imagenes a PDF"
    private const val MIME = "application/pdf"

    class Target(
        val uri: Uri,
        val stream: OutputStream,
        /** Solo en Android 8–9, donde se escribe un archivo real. */
        val file: File?
    )

    /** Limpia el nombre: quita caracteres inválidos, colapsa espacios y garantiza la extensión .pdf. */
    fun sanitize(name: String): String {
        var n = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(".")
        if (n.isEmpty()) n = "Documento"
        if (!n.lowercase().endsWith(".pdf")) n += ".pdf"
        return n
    }

    @Throws(IOException::class)
    fun create(context: Context, name: String): Target {
        val fileName = sanitize(name)
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore no devolvió un URI")
            val stream = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                throw IOException("No se pudo abrir el archivo para escritura")
            }
            return Target(uri, stream, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("No se pudo crear la carpeta de destino")
            val base = fileName.removeSuffix(".pdf")
            var file = File(dir, fileName)
            var k = 1
            while (file.exists()) { file = File(dir, "$base ($k).pdf"); k++ }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            return Target(uri, FileOutputStream(file), file)
        }
    }

    /** Publica el archivo (o lo borra si la generación falló). Devuelve el nombre final con el que quedó guardado. */
    fun finish(context: Context, target: Target, success: Boolean): String? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!success) {
                resolver.delete(target.uri, null, null)
                return null
            }
            resolver.update(target.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return queryDisplayName(context, target.uri)
        } else {
            val file = target.file ?: return null
            if (!success) {
                file.delete()
                return null
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME), null)
            return file.name
        }
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun querySize(context: Context, uri: Uri, file: File?): Long {
        if (file != null) return file.length()
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return -1
    }
}
