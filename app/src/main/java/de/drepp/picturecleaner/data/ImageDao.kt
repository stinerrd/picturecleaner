package de.drepp.picturecleaner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageDao {
    @Query("SELECT originalPath FROM images")
    suspend fun knownPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<ImageEntry>)

    @Update
    suspend fun update(entry: ImageEntry)

    @Update
    suspend fun updateAll(entries: List<ImageEntry>)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT score, sizeBytes FROM images WHERE state = 'SCORED'")
    fun observeScored(): Flow<List<ScoreRow>>

    @Query("SELECT * FROM images WHERE state = 'SCORED' AND score >= :threshold")
    suspend fun candidates(threshold: Float): List<ImageEntry>

    @Query("SELECT * FROM images WHERE state = 'QUARANTINED' ORDER BY score ASC")
    fun observeQuarantined(): Flow<List<ImageEntry>>

    @Query("SELECT * FROM images WHERE state = 'QUARANTINED'")
    suspend fun quarantined(): List<ImageEntry>

    @Query("SELECT * FROM images WHERE embedding IS NULL AND score >= 0")
    suspend fun withoutEmbedding(): List<ImageEntry>

    @Query("SELECT * FROM images WHERE embedding IS NOT NULL")
    suspend fun withEmbedding(): List<ImageEntry>

    @Query("SELECT * FROM images")
    suspend fun all(): List<ImageEntry>

    @Query("SELECT COUNT(*) FROM images WHERE state = 'KEPT'")
    fun observeKeptCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM images WHERE embedding IS NULL AND score >= 0")
    fun observeMissingEmbeddings(): Flow<Int>
}

@Dao
interface LabelDao {
    @Upsert
    suspend fun upsert(labels: List<Label>)

    @Query("SELECT * FROM labels")
    suspend fun all(): List<Label>

    @Query("DELETE FROM labels WHERE originalPath = :path")
    suspend fun delete(path: String)

    @Query("SELECT junk, COUNT(*) AS count FROM labels GROUP BY junk")
    fun observeCounts(): Flow<List<LabelCount>>
}

data class LabelCount(val junk: Boolean, val count: Int)
