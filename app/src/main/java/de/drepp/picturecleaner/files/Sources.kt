package de.drepp.picturecleaner.files

import android.os.Environment
import java.io.File

object Sources {
    private val root get() = Environment.getExternalStorageDirectory()

    val folders: List<File>
        get() = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
            File(root, "Pictures/Viber"),
            File(root, "Pictures/Telegram"),
            File(root, "Pictures/ChatGPT"),
        )

    private val extensions = setOf("jpg", "jpeg", "png", "webp")

    /**
     * Не сканируем и не трогаем: медиа заблокированных чатов WhatsApp и то,
     * что пользователь отправил сам.
     */
    private val excludedDirs = listOf("/WhatsApp Images/Private/", "/WhatsApp Images/Sent/")

    fun isExcluded(path: String): Boolean = excludedDirs.any { path.contains(it) }

    fun listImages(): List<File> = folders.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") && !isExcluded(it.path + "/") }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in extensions && !isExcluded(it.path) }
            .toList()
    }
}
