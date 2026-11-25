package de.drepp.picturecleaner.ui

import android.app.Application
import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import de.drepp.picturecleaner.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import de.drepp.picturecleaner.classify.ClipClassifier
import de.drepp.picturecleaner.classify.Ranker
import de.drepp.picturecleaner.classify.Scoring
import de.drepp.picturecleaner.classify.toBytes
import de.drepp.picturecleaner.classify.toFloats
import de.drepp.picturecleaner.data.AppDatabase
import de.drepp.picturecleaner.data.ImageEntry
import de.drepp.picturecleaner.data.ImageState
import de.drepp.picturecleaner.data.Label
import de.drepp.picturecleaner.files.Quarantine
import de.drepp.picturecleaner.files.SourceScope
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

data class Progress(@StringRes val label: Int, val done: Int, val total: Int)

data class ScanSummary(
    val scored: Int = 0,
    val candidates: Int = 0,
    val candidateBytes: Long = 0,
    val kept: Int = 0,
    val missingEmbeddings: Int = 0,
)

data class ModelInfo(val junk: Int = 0, val keep: Int = 0, val reference: Int = 0, val personalWeight: Float = 0f)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.images()
    private val labels = db.labels()
    private val quarantine = Quarantine(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scoring = Scoring.get(app)

    val threshold = MutableStateFlow(prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD))
    val includeDocuments = MutableStateFlow(scoring.includeDocuments)
    val scope = MutableStateFlow(SourceScope.load(app))
    val hasLockedChats = MutableStateFlow(false)
    val progress = MutableStateFlow<Progress?>(null)
    val selected = MutableStateFlow<Set<Long>>(emptySet())
    val message = MutableStateFlow<String?>(null)
    private val personalWeight = scoring.personalWeight

    val summary: StateFlow<ScanSummary> = combine(
        dao.observeScored(), dao.observeKeptCount(), dao.observeMissingEmbeddings(), threshold,
    ) { scored, kept, missing, t ->
        val hits = scored.filter { it.score >= t }
        ScanSummary(scored.size, hits.size, hits.sumOf { it.sizeBytes }, kept, missing)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScanSummary())

    val modelInfo: StateFlow<ModelInfo> = combine(labels.observeCounts(), labels.observeReferenceCount(), personalWeight) { counts, reference, w ->
        ModelInfo(
            junk = counts.firstOrNull { it.junk }?.count ?: 0,
            keep = (counts.firstOrNull { !it.junk }?.count ?: 0) - reference,
            reference = reference,
            personalWeight = w,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelInfo())

    val quarantined: StateFlow<List<ImageEntry>> =
        dao.observeQuarantined().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setThreshold(value: Float) {
        threshold.value = value
        prefs.edit().putFloat(KEY_THRESHOLD, value).apply()
    }

    fun setIncludeDocuments(value: Boolean) {
        includeDocuments.value = value
        scoring.includeDocuments = value
        runBusy { rescoreAll() }
    }

    fun setIncludePrivate(value: Boolean) = updateScope(scope.value.copy(includePrivate = value))

    fun setIncludeSent(value: Boolean) = updateScope(scope.value.copy(includeSent = value))

    private fun updateScope(value: SourceScope) {
        val narrowed = (scope.value.includePrivate && !value.includePrivate) || (scope.value.includeSent && !value.includeSent)
        scope.value = value
        value.save(getApplication())
        if (narrowed) runBusy {
            purgeExcluded()
            rescoreAll()
        }
    }

    fun scan() = runBusy {
        val known = dao.knownPaths().toHashSet()
        val newFiles = Sources.listImages(scope.value).filter { it.path !in known }
        val missing = dao.withoutEmbedding().filter { File(it.currentPath).exists() }
        val labeled = labels.all().map { it.originalPath }.toHashSet()
        val haveReferences = labeled.count(Sources::isReference)
        val references = Sources.listReferencePhotos()
            .filter { it.path !in labeled }
            .shuffled()
            .take((REFERENCE_PHOTOS - haveReferences).coerceAtLeast(0))
        val total = newFiles.size + missing.size + references.size
        var done = 0
        val ranker = buildRanker()

        ClipClassifier(getApplication()).use { classifier ->
            newFiles.chunked(BATCH).forEach { batch ->
                val entries = batch.map { file ->
                    val embedding = runCatching { classifier.embed(file) }.getOrNull()
                    ImageEntry(
                        originalPath = file.path,
                        currentPath = file.path,
                        sizeBytes = file.length(),
                        score = embedding?.let(ranker::score) ?: UNREADABLE,
                        state = ImageState.SCORED,
                        embedding = embedding?.toBytes(),
                    )
                }
                dao.insertAll(entries)
                done += batch.size
                progress.value = Progress(R.string.progress_checking, done, total)
            }

            missing.chunked(BATCH).forEach { batch ->
                val updated = batch.map { entry ->
                    val embedding = runCatching { classifier.embed(File(entry.currentPath)) }.getOrNull()
                    if (embedding == null) entry.copy(score = UNREADABLE)
                    else entry.copy(embedding = embedding.toBytes(), score = ranker.score(embedding))
                }
                dao.updateAll(updated)
                labels.upsert(
                    updated.filter { it.state == ImageState.KEPT && it.embedding != null }
                        .map { Label(it.originalPath, it.embedding!!, junk = false) },
                )
                done += batch.size
                progress.value = Progress(R.string.progress_checking, done, total)
            }

            references.chunked(BATCH).forEach { batch ->
                labels.upsert(
                    batch.mapNotNull { file ->
                        runCatching { classifier.embed(file) }.getOrNull()
                            ?.let { Label(file.path, it.toBytes(), junk = false) }
                    },
                )
                done += batch.size
                progress.value = Progress(R.string.progress_reference, done, total)
            }
        }
        rescoreAll()
        message.value = str(R.string.msg_checked, total)
    }

    fun moveCandidates() = runBusy {
        val candidates = dao.candidates(threshold.value)
        var failed = 0
        candidates.forEachIndexed { i, entry ->
            progress.value = Progress(R.string.progress_moving, i, candidates.size)
            val source = File(entry.currentPath)
            if (!source.exists()) {
                dao.delete(entry.id)
                return@forEachIndexed
            }
            val moved = quarantine.moveIn(source)
            if (moved == null) failed++
            else dao.update(entry.copy(currentPath = moved.path, state = ImageState.QUARANTINED))
        }
        message.value = str(R.string.msg_quarantined, candidates.size - failed) + if (failed > 0) ", " + str(R.string.msg_failed, failed) else ""
    }

    fun restoreSelected() = runBusy {
        val ids = selected.value
        val entries = dao.quarantined().filter { it.id in ids }
        val decided = mutableListOf<Label>()
        entries.forEachIndexed { i, entry ->
            progress.value = Progress(R.string.progress_restoring, i, entries.size)
            val back = quarantine.moveBack(File(entry.currentPath), entry.originalPath) ?: return@forEachIndexed
            dao.update(entry.copy(currentPath = back.path, state = ImageState.KEPT))
            entry.embedding?.let { decided += Label(entry.originalPath, it, junk = false) }
        }
        labels.upsert(decided)
        selected.value = emptySet()
        rescoreAll()
        message.value = str(R.string.msg_restored, entries.size)
    }

    fun deleteAllQuarantined() = runBusy {
        val entries = withEmbeddings(dao.quarantined())
        var freed = 0L
        var deleted = 0
        val decided = mutableListOf<Label>()
        entries.forEachIndexed { i, entry ->
            progress.value = Progress(R.string.progress_deleting, i, entries.size)
            val file = File(entry.currentPath)
            val size = file.length()
            if (quarantine.delete(file)) {
                freed += size
                deleted++
                entry.embedding?.let { decided += Label(entry.originalPath, it, junk = true) }
                dao.delete(entry.id)
            }
        }
        labels.upsert(decided)
        selected.value = emptySet()
        rescoreAll()
        message.value = str(R.string.msg_deleted, deleted, formatBytes(getApplication(), freed))
    }

    /** Досчитывает эмбеддинги перед удалением: после удаления файла метку уже не получить. */
    private suspend fun withEmbeddings(entries: List<ImageEntry>): List<ImageEntry> {
        val missing = entries.filter { it.embedding == null && File(it.currentPath).exists() }
        if (missing.isEmpty()) return entries
        val filled = HashMap<Long, ImageEntry>()
        ClipClassifier(getApplication()).use { classifier ->
            missing.forEachIndexed { i, entry ->
                progress.value = Progress(R.string.progress_preparing_delete, i, missing.size)
                runCatching { classifier.embed(File(entry.currentPath)) }.getOrNull()?.let {
                    filled[entry.id] = entry.copy(embedding = it.toBytes())
                }
            }
        }
        dao.updateAll(filled.values.toList())
        return entries.map { filled[it.id] ?: it }
    }

    /** Подхватывает правки, сделанные мимо приложения (например, в Галерее). */
    fun syncQuarantine() = viewModelScope.launch(Dispatchers.IO) {
        quarantine.protectHidden()
        hasLockedChats.value = Sources.hasLockedChatMedia()
        purgeExcluded()
        for (entry in dao.quarantined()) {
            if (File(entry.currentPath).exists()) continue
            if (File(entry.originalPath).exists()) {
                dao.update(entry.copy(currentPath = entry.originalPath, state = ImageState.KEPT))
                entry.embedding?.let { labels.upsert(listOf(Label(entry.originalPath, it, junk = false))) }
            } else {
                dao.delete(entry.id)
            }
        }
        rescoreAll()
    }

    /**
     * Картинки из исключённых папок возвращаются на место и забываются
     * целиком: ни записи в базе, ни метки, ни миниатюры в кэше.
     */
    private suspend fun purgeExcluded() {
        val excluded = dao.all().filter { Sources.isExcluded(it.originalPath, scope.value) }
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
            labels.delete(entry.originalPath)
        }
        getApplication<Application>().imageLoader.diskCache?.clear()
        getApplication<Application>().imageLoader.memoryCache?.clear()
        if (restored > 0) message.value = str(R.string.msg_purged, restored)
    }

    private suspend fun buildRanker(): Ranker = scoring.buildRanker()

    private suspend fun rescoreAll() = scoring.rescoreAll()

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }

    fun clearMessage() {
        message.value = null
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (progress.value != null) return
        progress.value = Progress(R.string.progress_preparing, 0, 0)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                message.value = str(R.string.msg_error, e.message.orEmpty())
            } finally {
                progress.value = null
            }
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.8f
        private const val REFERENCE_PHOTOS = 300
        const val UNREADABLE = -1f
        private const val BATCH = 16
        private const val KEY_THRESHOLD = "threshold_v2"
    }
}

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
