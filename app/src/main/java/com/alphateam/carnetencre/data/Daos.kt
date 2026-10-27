package com.alphateam.carnetencre.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Notebook>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun get(id: Long): Notebook?

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    suspend fun all(): List<Notebook>

    @Insert
    suspend fun insert(notebook: Notebook): Long

    @Update
    suspend fun update(notebook: Notebook)

    @Delete
    suspend fun delete(notebook: Notebook)

    @Query("UPDATE notebooks SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position")
    suspend fun pagesOf(notebookId: Long): List<Page>

    @Insert
    suspend fun insert(page: Page): Long

    @Delete
    suspend fun delete(page: Page)

    @Query("UPDATE pages SET position = position + 1 WHERE notebookId = :notebookId AND position >= :from")
    suspend fun shiftFrom(notebookId: Long, from: Int)

    @Query("UPDATE pages SET position = position - 1 WHERE notebookId = :notebookId AND position > :after")
    suspend fun unshiftAfter(notebookId: Long, after: Int)

    @Query("UPDATE pages SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE pages SET height = :height, updatedAt = :now WHERE id = :id")
    suspend fun updateHeight(id: Long, height: Float, now: Long)
}

@Dao
interface ElementDao {
    @Query("SELECT * FROM elements WHERE pageId = :pageId ORDER BY z")
    suspend fun elementsOf(pageId: Long): List<Element>

    @Query("DELETE FROM elements WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: Long)

    @Insert
    suspend fun insertAll(elements: List<Element>)

    @Transaction
    suspend fun replacePage(pageId: Long, elements: List<Element>) {
        deleteForPage(pageId)
        if (elements.isNotEmpty()) insertAll(elements)
    }
}

@Dao
interface AnswerDao {
    @Query("SELECT * FROM answers WHERE pageId = :pageId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(pageId: Long, limit: Int): List<Answer>

    @Insert
    suspend fun insert(answer: Answer): Long

    @Query(
        "DELETE FROM answers WHERE pageId = :pageId AND id NOT IN " +
            "(SELECT id FROM answers WHERE pageId = :pageId ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun trim(pageId: Long, keep: Int)
}
