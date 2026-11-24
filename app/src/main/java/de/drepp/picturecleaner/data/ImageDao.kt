package de.drepp.picturecleaner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT originalPath FROM images")
    suspend fun knownPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<ImageEntry>)

    @Update
    suspend fun update(entry: ImageEntry)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM images WHERE state = 'SCORED'")
    fun observeScored(): Flow<List<ImageEntry>>

    @Query("SELECT * FROM images WHERE state = 'SCORED' AND score >= :threshold")
    suspend fun candidates(threshold: Float): List<ImageEntry>

    @Query("SELECT * FROM images WHERE state = 'QUARANTINED' ORDER BY score ASC")
    fun observeQuarantined(): Flow<List<ImageEntry>>

    @Query("SELECT * FROM images WHERE state = 'QUARANTINED'")
    suspend fun quarantined(): List<ImageEntry>

    @Query("SELECT * FROM images")
    suspend fun all(): List<ImageEntry>

    @Query("SELECT COUNT(*) FROM images WHERE state = 'KEPT'")
    fun observeKeptCount(): Flow<Int>
}
