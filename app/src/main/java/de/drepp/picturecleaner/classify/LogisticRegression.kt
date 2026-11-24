package de.drepp.picturecleaner.classify

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Персональная модель поверх эмбеддингов CLIP: логистическая регрессия,
 * полный градиентный спуск. Классы взвешиваются поровну — удалённых картинок
 * обычно в разы больше, чем возвращённых.
 */
class LogisticRegression private constructor(private val weights: FloatArray, private val bias: Float) {

    fun predict(embedding: FloatArray): Float {
        val x = features(embedding)
        var z = bias
        for (i in x.indices) z += weights[i] * x[i]
        return sigmoid(z)
    }

    companion object {
        fun train(
            samples: List<FloatArray>,
            labels: List<Boolean>,
            epochs: Int = 300,
            learningRate: Float = 0.5f,
            l2: Float = 1e-3f,
        ): LogisticRegression {
            require(samples.size == labels.size && samples.isNotEmpty())
            val xs = samples.map(::features)
            val dim = xs[0].size
            val positives = labels.count { it }.coerceAtLeast(1)
            val negatives = (labels.size - positives).coerceAtLeast(1)
            val sampleWeights = labels.map { if (it) 0.5f / positives else 0.5f / negatives }

            val w = FloatArray(dim)
            var b = 0f
            val grad = FloatArray(dim)
            repeat(epochs) {
                grad.fill(0f)
                var gradB = 0f
                for (n in xs.indices) {
                    val x = xs[n]
                    var z = b
                    for (i in 0 until dim) z += w[i] * x[i]
                    val err = (sigmoid(z) - if (labels[n]) 1f else 0f) * sampleWeights[n]
                    for (i in 0 until dim) grad[i] += err * x[i]
                    gradB += err
                }
                for (i in 0 until dim) w[i] -= learningRate * (grad[i] + l2 * w[i])
                b -= learningRate * gradB
            }
            return LogisticRegression(w, b)
        }

        /** Нормированный эмбеддинг, растянутый до компонент порядка единицы. */
        private fun features(embedding: FloatArray): FloatArray {
            val norm = sqrt(embedding.fold(0f) { acc, v -> acc + v * v }).coerceAtLeast(1e-6f)
            val scale = sqrt(embedding.size.toFloat()) / norm
            return FloatArray(embedding.size) { embedding[it] * scale }
        }

        private fun sigmoid(z: Float): Float = 1f / (1f + exp(-z))
    }
}
