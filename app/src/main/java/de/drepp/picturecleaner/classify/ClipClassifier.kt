package de.drepp.picturecleaner.classify

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import de.drepp.picturecleaner.files.Sources
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ClipClassifier(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val model = context.assets.open("clip_visual.onnx").use { it.readBytes() }
        session = env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
    }

    /** Эмбеддинг CLIP; null, если файл не декодируется. Для видео — среднее по трём кадрам. */
    fun embed(file: File): FloatArray? {
        if (!Sources.isVideo(file.path)) return decode(file)?.let(::embedBitmap)
        val frames = videoFrames(file).mapNotNull { frame -> cropSquare(frame)?.let(::embedBitmap) }
        if (frames.isEmpty()) return null
        val mean = FloatArray(frames[0].size)
        for (f in frames) {
            val norm = sqrt(f.fold(0f) { acc, v -> acc + v * v }).coerceAtLeast(1e-6f)
            for (i in mean.indices) mean[i] += f[i] / norm / frames.size
        }
        return mean
    }

    private fun videoFrames(file: File): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            listOf(0.1, 0.5, 0.9).mapNotNull { at ->
                retriever.getScaledFrameAtTime((durationUs * at).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE * 2, SIZE * 2)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            retriever.release()
        }
    }

    private fun embedBitmap(bitmap: Bitmap): FloatArray {
        val input = toChw(bitmap)
        bitmap.recycle()
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf("image" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<FloatArray>)[0]
            }
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return cropSquare(raw)
    }

    private fun cropSquare(raw: Bitmap): Bitmap? {
        if (raw.width <= 0 || raw.height <= 0) return null
        val scale = SIZE.toFloat() / minOf(raw.width, raw.height)
        val w = max(SIZE, (raw.width * scale).roundToInt())
        val h = max(SIZE, (raw.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
        if (scaled !== raw) raw.recycle()
        val cropped = Bitmap.createBitmap(scaled, (w - SIZE) / 2, (h - SIZE) / 2, SIZE, SIZE)
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    private fun toChw(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        return out
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val SIZE = 256

        fun loadScorer(context: Context): ClipScorer {
            val json = JSONObject(context.assets.open("clip_text.json").bufferedReader().use { it.readText() })
            val groupsJson = json.getJSONObject("groups")
            val groups = groupsJson.keys().asSequence().associateWith { name ->
                val rows = groupsJson.getJSONArray(name)
                List(rows.length()) { i ->
                    val row = rows.getJSONArray(i)
                    FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
                }
            }
            return ClipScorer(groups, json.getDouble("logitScale").toFloat())
        }
    }
}
