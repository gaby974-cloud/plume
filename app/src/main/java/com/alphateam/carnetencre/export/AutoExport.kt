package com.alphateam.carnetencre.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.alphateam.carnetencre.data.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copie PDF automatique de chaque carnet :
 * - sur la liseuse, dans Documents/Plume (MediaStore, sans permission) ;
 * - sur Google Drive, dans un dossier « Plume », si Drive est connecté (voir [DriveSync]).
 *
 * L'URI de chaque copie locale est mémorisée pour la remplacer à la mise à jour suivante
 * plutôt que d'en créer une nouvelle.
 */
object AutoExport {
    const val FOLDER = "Documents/Plume"

    private fun prefs(context: Context) = context.getSharedPreferences("copies_pdf", Context.MODE_PRIVATE)

    /** Résultat d'une publication : copie locale écrite, envoi Drive (null si Drive n'est pas connecté). */
    class Result(val local: Boolean, val drive: Boolean?)

    /** Rend le carnet en PDF (toutes ses pages, telles qu'enregistrées) et publie ses copies. */
    suspend fun publishNotebook(context: Context, repo: NotesRepository, notebookId: Long, title: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val pages = repo.pages(notebookId).map { repo.loadElements(it.id) to it.height }
                val pdf = Exporter.notebookPdf(context, "copie-$notebookId", pages)
                val name = fileName(title, notebookId)
                val local = publishToDocuments(context, notebookId, name, pdf.file)
                val drive = if (DriveSync.isConnected(context)) DriveSync.upload(context, notebookId, name, pdf.file) else null
                pdf.file.delete()
                Result(local, drive)
            } catch (e: Exception) {
                Result(local = false, drive = null)
            }
        }

    /** Publie les copies de tous les carnets ; renvoie (copies locales, envois Drive réussis). */
    suspend fun publishAll(context: Context, repo: NotesRepository): Pair<Int, Int> {
        var local = 0
        var drive = 0
        for (nb in repo.allNotebooks()) {
            val r = publishNotebook(context, repo, nb.id, nb.title)
            if (r.local) local++
            if (r.drive == true) drive++
        }
        return local to drive
    }

    private fun publishToDocuments(context: Context, notebookId: Long, name: String, pdf: File): Boolean {
        val resolver = context.contentResolver
        val known = prefs(context).getString(notebookId.toString(), null)?.let(Uri::parse)
        if (known != null && write(context, known, pdf)) {
            // Le carnet a pu être renommé depuis la dernière copie.
            try {
                resolver.update(known, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
            } catch (_: Exception) {
            }
            return true
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$FOLDER/")
        }
        val uri = try {
            resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (e: Exception) {
            null
        } ?: return false
        if (!write(context, uri, pdf)) return false
        prefs(context).edit().putString(notebookId.toString(), uri.toString()).apply()
        return true
    }

    /** Retire les copies d'un carnet supprimé (la copie Drive part à la corbeille de Drive). */
    suspend fun remove(context: Context, notebookId: Long) {
        val p = prefs(context)
        p.getString(notebookId.toString(), null)?.let(Uri::parse)?.let { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
        p.edit().remove(notebookId.toString()).apply()
        DriveSync.trash(context, notebookId)
    }

    private fun write(context: Context, uri: Uri, pdf: File): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            pdf.inputStream().use { it.copyTo(out) }
        } != null
    } catch (e: Exception) {
        false
    }

    /** Titre lisible et identifiant du carnet, pour que deux carnets homonymes ne s'écrasent pas. */
    fun fileName(title: String, notebookId: Long): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Carnet" }
        return "$clean ($notebookId).pdf"
    }
}
