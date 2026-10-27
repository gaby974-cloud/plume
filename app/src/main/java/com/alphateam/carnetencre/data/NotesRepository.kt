package com.alphateam.carnetencre.data

import androidx.room.withTransaction
import com.alphateam.carnetencre.model.ElementCodec
import com.alphateam.carnetencre.model.PageElement
import kotlinx.coroutines.flow.Flow

class NotesRepository(private val db: AppDatabase) {

    fun observeNotebooks(): Flow<List<Notebook>> = db.notebooks().observeAll()

    suspend fun notebook(id: Long): Notebook? = db.notebooks().get(id)

    suspend fun allNotebooks(): List<Notebook> = db.notebooks().all()

    /** Crée un carnet (pages successives ou page infinie) avec une première page vide. */
    suspend fun createNotebook(title: String, infinite: Boolean): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = db.notebooks().insert(Notebook(title = title, createdAt = now, updatedAt = now, infinite = infinite))
        db.pages().insert(Page(notebookId = id, position = 0, createdAt = now, updatedAt = now))
        id
    }

    suspend fun renameNotebook(notebook: Notebook, title: String) =
        db.notebooks().update(notebook.copy(title = title, updatedAt = System.currentTimeMillis()))

    suspend fun deleteNotebook(notebook: Notebook) = db.notebooks().delete(notebook)

    suspend fun pages(notebookId: Long): List<Page> = db.pages().pagesOf(notebookId)

    /** Insère une page juste après [position] et renvoie son identifiant. */
    suspend fun insertPageAfter(notebookId: Long, position: Int): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        db.pages().shiftFrom(notebookId, position + 1)
        val id = db.pages().insert(Page(notebookId = notebookId, position = position + 1, createdAt = now, updatedAt = now))
        db.notebooks().touch(notebookId, now)
        id
    }

    suspend fun deletePage(page: Page) = db.withTransaction {
        db.pages().delete(page)
        db.pages().unshiftAfter(page.notebookId, page.position)
        db.notebooks().touch(page.notebookId, System.currentTimeMillis())
    }

    suspend fun loadElements(pageId: Long): List<PageElement> =
        db.elements().elementsOf(pageId).mapNotNull { ElementCodec.fromEntity(it) }

    suspend fun saveElements(page: Page, elements: List<PageElement>, height: Float) {
        val entities = elements.map { ElementCodec.toEntity(it, page.id) }
        db.withTransaction {
            db.elements().replacePage(page.id, entities)
            val now = System.currentTimeMillis()
            db.pages().updateHeight(page.id, height, now)
            db.notebooks().touch(page.notebookId, now)
        }
    }

    suspend fun saveAnswer(answer: Answer) = db.withTransaction {
        db.answers().insert(answer)
        db.answers().trim(answer.pageId, HISTORY_SIZE)
    }

    suspend fun recentAnswers(pageId: Long): List<Answer> = db.answers().recent(pageId, HISTORY_SIZE)

    companion object {
        const val HISTORY_SIZE = 8
    }
}
