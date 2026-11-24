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

    /**
     * Заведомо настоящие фото. Не кандидаты — только образцы «оставить» для
     * персональной модели: без них она видит лишь удалённые открытки и не
     * знает, куда девать обычные семейные фото.
     */
    val referenceFolder: File get() = File(root, "DCIM/Camera")

    fun isReference(path: String): Boolean = path.startsWith(referenceFolder.path + "/")

    fun listReferencePhotos(): List<File> = referenceFolder.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in extensions }
        .orEmpty()

    fun listImages(): List<File> = folders.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") && !isExcluded(it.path + "/") }
            .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in extensions && !isExcluded(it.path) }
            .toList()
    }
}
