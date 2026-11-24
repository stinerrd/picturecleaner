package de.drepp.picturecleaner.files

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

class Quarantine(private val context: Context) {
    val dir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PictureCleaner")

    /** Переносит файл в карантин; возвращает новый путь или null при неудаче. */
    fun moveIn(source: File): File? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = freeName(dir, source.name)
        return move(source, target)
    }

    /** Возвращает файл по исходному пути (или рядом, если имя занято). */
    fun moveBack(current: File, originalPath: String): File? {
        val original = File(originalPath)
        val parent = original.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs()) return null
        return move(current, freeName(parent, original.name))
    }

    fun delete(file: File): Boolean {
        val ok = !file.exists() || file.delete()
        if (ok) rescan(file)
        return ok
    }

    private fun move(source: File, target: File): File? {
        val modified = source.lastModified()
        val ok = source.renameTo(target) || runCatching {
            source.copyTo(target)
            target.setLastModified(modified)
            source.delete()
        }.getOrDefault(false)
        if (!ok) return null
        rescan(source, target)
        return target
    }

    private fun freeName(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${name.substringBeforeLast('.')}_$n.${name.substringAfterLast('.', "jpg")}")
            n++
        }
        return candidate
    }

    private fun rescan(vararg files: File) {
        MediaScannerConnection.scanFile(context, files.map { it.path }.toTypedArray(), null, null)
    }
}
