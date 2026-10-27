package com.alphateam.carnetencre.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.alphateam.carnetencre.tr
import com.alphateam.carnetencre.model.PAGE_H
import com.alphateam.carnetencre.model.PAGE_W
import com.alphateam.carnetencre.model.PageElement
import com.alphateam.carnetencre.render.PageRenderer
import android.graphics.RectF
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

/** Export d'une page en PNG ou d'un carnet en PDF, puis partage ou copie dans Téléchargements. */
object Exporter {

    class ExportedFile(val file: File, val mime: String)

    /** Page entière, même allongée ; au plus ~20 mégapixels pour tenir en mémoire. */
    fun pagePng(context: Context, baseName: String, elements: List<PageElement>, height: Float): ExportedFile {
        val longest = maxOf(PAGE_W, height)
        val scale = minOf(1f, sqrt(MAX_PIXELS / (PAGE_W * height)))
        val bmp = PageRenderer.renderBitmap(elements, RectF(0f, 0f, PAGE_W, height), (longest * scale).toInt())
        val file = File(exportDir(context), "${safe(baseName)}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return ExportedFile(file, "image/png")
    }

    /**
     * PDF vectoriel au format 3:4. Une page allongée (réponses de Claude, page infinie)
     * est découpée en plusieurs pages PDF successives.
     */
    fun notebookPdf(context: Context, baseName: String, pages: List<Pair<List<PageElement>, Float>>): ExportedFile {
        val pdf = PdfDocument()
        val widthPt = 600
        val heightPt = (widthPt * PAGE_H / PAGE_W).toInt()
        val s = widthPt / PAGE_W
        var number = 1
        try {
            for ((elements, height) in pages) {
                val chunks = ceil(height / PAGE_H).toInt().coerceAtLeast(1)
                for (k in 0 until chunks) {
                    val top = k * PAGE_H
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(widthPt, heightPt, number++).create())
                    val c = page.canvas
                    c.drawColor(Color.WHITE)
                    c.scale(s, s)
                    c.translate(0f, -top)
                    c.clipRect(0f, top, PAGE_W, top + PAGE_H)
                    PageRenderer.drawElements(c, elements, RectF(0f, top, PAGE_W, top + PAGE_H))
                    pdf.finishPage(page)
                }
            }
            val file = File(exportDir(context), "${safe(baseName)}.pdf")
            file.outputStream().use { pdf.writeTo(it) }
            return ExportedFile(file, "application/pdf")
        } finally {
            pdf.close()
        }
    }

    fun share(context: Context, exported: ExportedFile) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fichiers", exported.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = exported.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, exported.file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, tr("Partager", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Copie le fichier dans Téléchargements/Plume. Renvoie vrai en cas de succès. */
    fun saveToDownloads(context: Context, exported: ExportedFile): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, exported.file.name)
            put(MediaStore.Downloads.MIME_TYPE, exported.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Plume")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            false
        } else {
            resolver.openOutputStream(uri)?.use { out -> exported.file.inputStream().use { it.copyTo(out) } }
            true
        }
    } catch (e: Exception) {
        false
    }

    private const val MAX_PIXELS = 20_000_000f

    private fun exportDir(context: Context) = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun safe(name: String) = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "carnet" }
}
