package de.drepp.picturecleaner.classify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LogisticRegressionTest {
    private val random = Random(42)

    private fun cluster(center: FloatArray, n: Int) = List(n) {
        FloatArray(center.size) { i -> center[i] + (random.nextFloat() - 0.5f) * 0.6f }
    }

    @Test
    fun separatesTwoClusters() {
        val junkCenter = FloatArray(16) { if (it < 8) 1f else 0f }
        val keepCenter = FloatArray(16) { if (it < 8) 0f else 1f }
        val model = LogisticRegression.train(
            cluster(junkCenter, 200) + cluster(keepCenter, 20),
            List(200) { true } + List(20) { false },
        )
        assertTrue(model.predict(junkCenter) > 0.9f)
        assertTrue(model.predict(keepCenter) < 0.1f)
    }

    @Test
    fun embeddingBytesRoundTrip() {
        val original = floatArrayOf(0.5f, -1.25f, 3e-4f)
        assertArrayEquals(original, original.toBytes().toFloats(), 0f)
    }

    @Test
    fun rankerUsesZeroShotUntilEnoughLabels() {
        val scorer = ClipScorer(
            mapOf(
                "photo" to listOf(floatArrayOf(1f, 0f)),
                "generated" to listOf(floatArrayOf(0f, 1f)),
                "document" to listOf(floatArrayOf(0.7f, 0.7f)),
            ),
            logitScale = 10f,
        )
        val e = floatArrayOf(0.2f, 0.9f)
        val few = Ranker.build(scorer, includeDocuments = false, samples = List(5) { floatArrayOf(0f, 1f) }, junk = List(5) { true })
        assertEquals(0f, few.personalWeight)
        assertEquals(few.zeroShot(e), few.score(e), 1e-6f)
        val withDocs = Ranker.build(scorer, includeDocuments = true, samples = emptyList(), junk = emptyList())
        assertTrue(withDocs.zeroShot(e) > few.zeroShot(e))
    }
}
