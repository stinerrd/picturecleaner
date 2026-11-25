package de.drepp.picturecleaner.files

import android.content.Context
import android.os.Environment
import java.io.File

data class SourceScope(val includePrivate: Boolean = false, val includeSent: Boolean = false) {
    fun save(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_PRIVATE, includePrivate)
            .putBoolean(KEY_SENT, includeSent)
            .apply()
    }

    companion object {
        private const val KEY_PRIVATE = "includePrivate"
        private const val KEY_SENT = "includeSent"

        private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(context: Context): SourceScope = prefs(context).let {
            SourceScope(it.getBoolean(KEY_PRIVATE, false), it.getBoolean(KEY_SENT, false))
        }
    }
}

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
    private val privateFolder = Regex("/WhatsApp [^/]+/Private(/|$)")
    private val sentFolder = Regex("/WhatsApp [^/]+/Sent(/|$)")

    fun isPrivate(path: String): Boolean = privateFolder.containsMatchIn(path)

    fun isExcluded(path: String, scope: SourceScope): Boolean =
        (!scope.includePrivate && isPrivate(path)) || (!scope.includeSent && sentFolder.containsMatchIn(path))

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

    fun listImages(scope: SourceScope): List<File> = folders.filter { it.isDirectory }.flatMap { dir ->
        dir.walkTopDown()
            .onEnter { !it.name.startsWith(".") && !isExcluded(it.path + "/", scope) }
            .filter {
                it.isFile && !it.name.startsWith(".") && !isExcluded(it.path, scope) &&
                    it.extension.lowercase().let { ext -> ext in imageExtensions || ext in videoExtensions }
            }
            .toList()
    }
}
