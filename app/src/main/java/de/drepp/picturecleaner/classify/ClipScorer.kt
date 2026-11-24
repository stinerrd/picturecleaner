package de.drepp.picturecleaner.classify

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Zero-shot по группам промптов: softmax по всем промптам сразу, затем сумма
 * вероятностей внутри группы. Так же считает calibration/score.py — пороги,
 * подобранные там, переносятся сюда без пересчёта.
 */
class ClipScorer(
    private val groups: Map<String, List<FloatArray>>,
    private val logitScale: Float,
) {
    fun score(embedding: FloatArray): Map<String, Float> {
        val norm = sqrt(embedding.fold(0f) { acc, v -> acc + v * v })
        val logits = groups.mapValues { (_, prompts) ->
            prompts.map { p -> logitScale * dot(embedding, p) / norm }
        }
        val max = logits.values.flatten().max()
        val exps = logits.mapValues { (_, l) -> l.map { exp((it - max).toDouble()) } }
        val total = exps.values.flatten().sum()
        return exps.mapValues { (_, e) -> (e.sum() / total).toFloat() }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
