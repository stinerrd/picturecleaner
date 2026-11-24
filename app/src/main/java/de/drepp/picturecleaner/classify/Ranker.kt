package de.drepp.picturecleaner.classify

/**
 * Итоговый балл «мусорности». Старт — zero-shot CLIP; по мере накопления
 * решений пользователя вес переходит к персональной модели, но zero-shot
 * не исчезает совсем — он держит то, о чём решений ещё не было.
 */
class Ranker(
    private val scorer: ClipScorer,
    private val includeDocuments: Boolean,
    private val personal: LogisticRegression?,
    labelCount: Int,
) {
    val personalWeight: Float = if (personal == null) 0f else labelCount / (labelCount + PRIOR_STRENGTH)

    fun zeroShot(embedding: FloatArray): Float {
        val p = scorer.score(embedding)
        return (p["generated"] ?: 0f) + if (includeDocuments) p["document"] ?: 0f else 0f
    }

    fun score(embedding: FloatArray): Float {
        val prior = zeroShot(embedding)
        val model = personal ?: return prior
        return personalWeight * model.predict(embedding) + (1 - personalWeight) * prior
    }

    companion object {
        const val MIN_PER_CLASS = 10
        private const val PRIOR_STRENGTH = 100f

        fun build(scorer: ClipScorer, includeDocuments: Boolean, samples: List<FloatArray>, junk: List<Boolean>): Ranker {
            val positives = junk.count { it }
            val trainable = positives >= MIN_PER_CLASS && junk.size - positives >= MIN_PER_CLASS
            val model = if (trainable) LogisticRegression.train(samples, junk) else null
            return Ranker(scorer, includeDocuments, model, junk.size)
        }
    }
}
