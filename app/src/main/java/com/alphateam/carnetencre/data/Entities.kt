package com.alphateam.carnetencre.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class Notebook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Vrai : une seule page qui s'allonge vers le bas. Faux : pages successives. */
    @ColumnInfo(defaultValue = "0") val infinite: Boolean = false,
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(
        entity = Notebook::class,
        parentColumns = ["id"],
        childColumns = ["notebookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("notebookId")],
)
data class Page(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notebookId: Long,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** Hauteur en unités de page : 1872 au départ, plus si la page s'est allongée. */
    @ColumnInfo(defaultValue = "1872") val height: Float = 1872f,
)

/** Un élément de page : trait manuscrit ou bloc texte de Claude. `payload` est encodé par ElementCodec. */
@Entity(
    tableName = "elements",
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("pageId")],
)
class Element(
    @PrimaryKey val id: String,
    val pageId: Long,
    val type: Int,
    val payload: ByteArray,
    val z: Int,
) {
    companion object {
        const val STROKE = 0
        const val TEXT = 1
        const val FIGURE = 2
    }
}

@Entity(
    tableName = "answers",
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("pageId")],
)
data class Answer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val mode: String,
    val text: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val createdAt: Long,
)
