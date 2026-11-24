package de.drepp.picturecleaner.classify

import android.content.Context
import de.drepp.picturecleaner.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Обучение и пересчёт баллов, общие для главного экрана и «Поделиться».
 * Одна на процесс: пересчёт под замком, чтобы два источника не писали
 * баллы наперегонки.
 */
class Scoring private constructor(context: Context) {
    private val db = AppDatabase.get(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scorer by lazy { ClipClassifier.loadScorer(context) }
    private val lock = Mutex()

    val personalWeight = MutableStateFlow(0f)

    var includeDocuments: Boolean
        get() = prefs.getBoolean(KEY_DOCUMENTS, true)
        set(value) = prefs.edit().putBoolean(KEY_DOCUMENTS, value).apply()

    suspend fun buildRanker(): Ranker = withContext(Dispatchers.Default) {
        val all = db.labels().all()
        Ranker.build(scorer, includeDocuments, all.map { it.embedding.toFloats() }, all.map { it.junk })
            .also { personalWeight.value = it.personalWeight }
    }

    /** Переобучает персональную модель и пересчитывает баллы по сохранённым эмбеддингам. */
    suspend fun rescoreAll() = lock.withLock {
        val ranker = buildRanker()
        val dao = db.images()
        val rows = dao.withEmbedding()
        val updated = withContext(Dispatchers.Default) {
            rows.mapNotNull { entry ->
                val score = ranker.score(entry.embedding!!.toFloats())
                if (score != entry.score) entry.copy(score = score) else null
            }
        }
        updated.chunked(500).forEach { dao.updateAll(it) }
    }

    companion object {
        private const val KEY_DOCUMENTS = "includeDocuments"

        @Volatile private var instance: Scoring? = null

        fun get(context: Context): Scoring = instance ?: synchronized(this) {
            instance ?: Scoring(context.applicationContext).also { instance = it }
        }
    }
}
