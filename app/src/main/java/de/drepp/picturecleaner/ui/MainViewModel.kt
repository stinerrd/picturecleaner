package de.drepp.picturecleaner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import de.drepp.picturecleaner.classify.ClipClassifier
import de.drepp.picturecleaner.data.AppDatabase
import de.drepp.picturecleaner.data.ImageEntry
import de.drepp.picturecleaner.data.ImageState
import de.drepp.picturecleaner.files.Quarantine
import de.drepp.picturecleaner.files.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Progress(val label: String, val done: Int, val total: Int)

data class ScanSummary(
    val scored: Int = 0,
    val candidates: Int = 0,
    val candidateBytes: Long = 0,
    val kept: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).images()
    private val quarantine = Quarantine(app)

    val threshold = MutableStateFlow(DEFAULT_THRESHOLD)
    val progress = MutableStateFlow<Progress?>(null)
    val selected = MutableStateFlow<Set<Long>>(emptySet())
    val message = MutableStateFlow<String?>(null)

    val summary: StateFlow<ScanSummary> = combine(dao.observeScored(), dao.observeKeptCount(), threshold) { scored, kept, t ->
        val hits = scored.filter { it.score >= t }
        ScanSummary(scored.size, hits.size, hits.sumOf { it.sizeBytes }, kept)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScanSummary())

    val quarantined: StateFlow<List<ImageEntry>> =
        dao.observeQuarantined().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun scan() = runBusy {
        val known = dao.knownPaths().toHashSet()
        val files = Sources.listImages().filter { it.path !in known }
        progress.value = Progress("Проверка", 0, files.size)
        ClipClassifier(getApplication()).use { classifier ->
            files.chunked(BATCH).forEachIndexed { i, batch ->
                val entries = batch.map { file ->
                    val score = runCatching { classifier.generatedScore(file) }.getOrNull() ?: UNREADABLE
                    ImageEntry(
                        originalPath = file.path,
                        currentPath = file.path,
                        sizeBytes = file.length(),
                        score = score,
                        state = ImageState.SCORED,
                    )
                }
                dao.insertAll(entries)
                progress.value = Progress("Проверка", minOf((i + 1) * BATCH, files.size), files.size)
            }
        }
        message.value = "Проверено новых картинок: ${files.size}"
    }

    fun moveCandidates() = runBusy {
        val candidates = dao.candidates(threshold.value)
        var failed = 0
        candidates.forEachIndexed { i, entry ->
            progress.value = Progress("Перенос в карантин", i, candidates.size)
            val source = File(entry.currentPath)
            if (!source.exists()) {
                dao.delete(entry.id)
                return@forEachIndexed
            }
            val moved = quarantine.moveIn(source)
            if (moved == null) failed++
            else dao.update(entry.copy(currentPath = moved.path, state = ImageState.QUARANTINED))
        }
        message.value = "В карантине: ${candidates.size - failed}" + if (failed > 0) ", не удалось: $failed" else ""
    }

    fun restoreSelected() = runBusy {
        val ids = selected.value
        val entries = dao.quarantined().filter { it.id in ids }
        entries.forEachIndexed { i, entry ->
            progress.value = Progress("Возврат", i, entries.size)
            val back = quarantine.moveBack(File(entry.currentPath), entry.originalPath)
            if (back != null) dao.update(entry.copy(currentPath = back.path, state = ImageState.KEPT))
        }
        selected.value = emptySet()
        message.value = "Возвращено: ${entries.size}"
    }

    fun deleteAllQuarantined() = runBusy {
        val entries = dao.quarantined()
        var freed = 0L
        entries.forEachIndexed { i, entry ->
            progress.value = Progress("Удаление", i, entries.size)
            val file = File(entry.currentPath)
            val size = file.length()
            if (quarantine.delete(file)) {
                freed += size
                dao.delete(entry.id)
            }
        }
        selected.value = emptySet()
        message.value = "Удалено ${entries.size}, освобождено ${formatBytes(freed)}"
    }

    /** Подхватывает правки, сделанные мимо приложения (например, в Галерее). */
    fun syncQuarantine() = viewModelScope.launch(Dispatchers.IO) {
        purgeExcluded()
        for (entry in dao.quarantined()) {
            if (File(entry.currentPath).exists()) continue
            if (File(entry.originalPath).exists()) {
                dao.update(entry.copy(currentPath = entry.originalPath, state = ImageState.KEPT))
            } else {
                dao.delete(entry.id)
            }
        }
    }

    /**
     * Картинки из исключённых папок (закрытые чаты) возвращаются на место и
     * забываются целиком: ни записи в базе, ни миниатюры в кэше.
     */
    private suspend fun purgeExcluded() {
        val excluded = dao.all().filter { Sources.isExcluded(it.originalPath) }
        if (excluded.isEmpty()) return
        var restored = 0
        for (entry in excluded) {
            if (entry.state == ImageState.QUARANTINED && entry.currentPath != entry.originalPath) {
                val current = File(entry.currentPath)
                if (current.exists()) {
                    if (quarantine.moveBack(current, entry.originalPath) == null) continue
                    restored++
                }
            }
            dao.delete(entry.id)
        }
        getApplication<Application>().imageLoader.diskCache?.clear()
        getApplication<Application>().imageLoader.memoryCache?.clear()
        if (restored > 0) message.value = "Возвращено из карантина в закрытые чаты: $restored"
    }

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }

    fun clearMessage() {
        message.value = null
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (progress.value != null) return
        progress.value = Progress("", 0, 0)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                message.value = "Ошибка: ${e.message}"
            } finally {
                progress.value = null
            }
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val UNREADABLE = -1f
        private const val BATCH = 16
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f КБ".format(bytes / 1024.0)
}
