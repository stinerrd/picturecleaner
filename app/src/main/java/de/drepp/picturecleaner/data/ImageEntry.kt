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
)
