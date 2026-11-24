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

    fun listImages(): List<File> = folders.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in extensions }
            .toList()
    }
}
