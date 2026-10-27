package com.alphateam.carnetencre.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Notebook::class, Page::class, Element::class, Answer::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notebooks(): NotebookDao
    abstract fun pages(): PageDao
    abstract fun elements(): ElementDao
    abstract fun answers(): AnswerDao

    companion object {
        /** v2 : mode « page infinie » des carnets et hauteur variable des pages. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN infinite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN height REAL NOT NULL DEFAULT 1872")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "carnet-encre.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
