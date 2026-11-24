package de.drepp.picturecleaner.files

import android.os.Environment
import java.io.File

object Sources {
    private val root get() = Environment.getExternalStorageDirectory()
    private val whatsapp get() = File(root, "Android/media/com.whatsapp/WhatsApp/Media")

    val folders: List<File>
        get() = listOf(
            File(whatsapp, "WhatsApp Images"),
            File(whatsapp, "WhatsApp Video"),
            File(whatsapp, "WhatsApp Animated Gifs"),
            File(root, "Pictures/Viber"),
            File(root, "Movies/Viber"),
            File(root, "Pictures/Telegram"),
            File(root, "Movies/Telegram"),
            File(root, "Pictures/ChatGPT"),
        )

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val videoExtensions = setOf("mp4", "3gp", "mkv", "webm", "mov")

    fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in videoExtensions

    /**
     * Не сканируем и не трогаем: медиа заблокированных чатов WhatsApp и то,
     * что пользователь отправил сам — в любом разделе WhatsApp.
     */
    private val excluded = Regex("/WhatsApp [^/]+/(Private|Sent)(/|$)")

    fun isExcluded(path: String): Boolean = excluded.containsMatchIn(path)

    /**
     * Заведомо настоящие фото. Не кандидаты — только образцы «оставить» для
     * персональной модели: без них она видит лишь удалённые открытки и не
     * знает, куда девать обычные семейные фото.
     */
    val referenceFolder: File get() = File(root, "DCIM/Camera")

    fun isReference(path: String): Boolean = path.startsWith(referenceFolder.path + "/")

    fun listReferencePhotos(): List<File> = referenceFolder.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in imageExtensions }
        .orEmpty()

    fun listImages(): List<File> = folders.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") && !isExcluded(it.path + "/") }
            .filter {
                it.isFile && !it.name.startsWith(".") && !isExcluded(it.path) &&
                    it.extension.lowercase().let { ext -> ext in imageExtensions || ext in videoExtensions }
            }
            .toList()
    }
}
