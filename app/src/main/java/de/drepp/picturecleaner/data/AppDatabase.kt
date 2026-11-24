package de.drepp.picturecleaner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ImageEntry::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun images(): ImageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "picturecleaner.db")
                .build().also { instance = it }
        }
    }
}
