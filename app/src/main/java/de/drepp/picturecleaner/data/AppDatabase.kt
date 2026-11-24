package de.drepp.picturecleaner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ImageEntry::class, Label::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun images(): ImageDao
    abstract fun labels(): LabelDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN embedding BLOB")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS labels (originalPath TEXT NOT NULL, embedding BLOB NOT NULL, " +
                        "junk INTEGER NOT NULL, decidedAt INTEGER NOT NULL, PRIMARY KEY(originalPath))",
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "picturecleaner.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
