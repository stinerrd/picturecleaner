package de.drepp.picturecleaner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ImageState { SCORED, QUARANTINED, KEPT }

@Entity(tableName = "images", indices = [Index(value = ["originalPath"], unique = true)])
data class ImageEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val currentPath: String,
    val sizeBytes: Long,
    val score: Float,
    val state: ImageState,
    val embedding: ByteArray? = null,
)

data class ScoreRow(val score: Float, val sizeBytes: Long)

/**
 * Решение пользователя по картинке: удалил (junk) или вернул. Живёт дольше
 * самого файла — на этих метках учится персональная модель.
 */
@Entity(tableName = "labels")
data class Label(
    @PrimaryKey val originalPath: String,
    val embedding: ByteArray,
    val junk: Boolean,
    val decidedAt: Long = System.currentTimeMillis(),
)
