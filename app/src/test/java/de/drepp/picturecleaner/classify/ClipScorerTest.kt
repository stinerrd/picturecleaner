package de.drepp.picturecleaner.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipScorerTest {
    private val scorer = ClipScorer(
        groups = mapOf(
            "photo" to listOf(floatArrayOf(1f, 0f, 0f)),
            "generated" to listOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0.8f, 0.6f)),
            "document" to listOf(floatArrayOf(0f, 0f, 1f)),
        ),
        logitScale = 100f,
    )

    @Test
    fun groupProbabilitiesSumToOne() {
        val scores = scorer.score(floatArrayOf(0.3f, 0.5f, 0.2f))
        assertEquals(1f, scores.values.sum(), 1e-5f)
    }

    @Test
    fun embeddingNearGeneratedPromptsScoresHigh() {
        assertTrue(scorer.score(floatArrayOf(0f, 2f, 0.1f))["generated"]!! > 0.99f)
        assertTrue(scorer.score(floatArrayOf(5f, 0f, 0f))["generated"]!! < 0.01f)
    }

    @Test
    fun scoreDoesNotDependOnEmbeddingLength() {
        val a = scorer.score(floatArrayOf(0.2f, 0.3f, 0.1f))["generated"]!!
        val b = scorer.score(floatArrayOf(2f, 3f, 1f))["generated"]!!
        assertEquals(a, b, 1e-5f)
    }
}
