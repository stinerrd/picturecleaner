package de.drepp.picturecleaner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.drepp.picturecleaner.classify.ClipClassifier
import de.drepp.picturecleaner.classify.Scoring
import de.drepp.picturecleaner.classify.toBytes
import de.drepp.picturecleaner.data.AppDatabase
import de.drepp.picturecleaner.data.ImageEntry
import de.drepp.picturecleaner.data.ImageState
import de.drepp.picturecleaner.data.Label
import de.drepp.picturecleaner.files.Quarantine
import de.drepp.picturecleaner.files.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Поделиться → В мусор» из Галереи: картинка уходит в карантин и сразу
 * становится примером «мусор» для персональной модели.
 */
class ShareActivity : ComponentActivity() {
    private val status = mutableStateOf("")
    private val fraction = mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status.value = getString(R.string.progress_preparing)
        setContent {
            MaterialTheme(colorScheme = de.drepp.picturecleaner.ui.appColorScheme()) {
                Card {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleMedium)
                        Text(status.value)
                        LinearProgressIndicator(progress = { fraction.value })
                    }
                }
            }
        }

        if (!Environment.isExternalStorageManager()) {
            finishWith(getString(R.string.share_no_access))
            return
        }
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finishWith(getString(R.string.share_nothing))
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { process(uris) }
            finishWith(result)
        }
    }

    private suspend fun process(uris: List<Uri>): String {
        val db = AppDatabase.get(this)
        val dao = db.images()
        val quarantine = Quarantine(this)
        var moved = 0
        var excluded = 0
        var failed = 0
        val labels = mutableListOf<Label>()

        ClipClassifier(this).use { classifier ->
            uris.forEachIndexed { i, uri ->
                status.value = getString(R.string.share_processing, i + 1, uris.size)
                fraction.value = i.toFloat() / uris.size
                val file = resolveFile(uri)
                when {
                    file == null || !file.exists() -> failed++
                    Sources.isExcluded(file.path) -> excluded++
                    else -> {
                        val existing = dao.findByPath(file.path)
                        val embedding = existing?.embedding
                            ?: runCatching { classifier.embed(file) }.getOrNull()?.toBytes()
                        val inQuarantine = existing?.state == ImageState.QUARANTINED
                        val target = if (inQuarantine) file else quarantine.moveIn(file)
                        if (target == null || embedding == null) {
                            failed++
                        } else {
                            val originalPath = existing?.originalPath ?: file.path
                            val entry = (existing ?: ImageEntry(
                                originalPath = originalPath,
                                currentPath = file.path,
                                sizeBytes = file.length(),
                                score = 1f,
                                state = ImageState.SCORED,
                            )).copy(currentPath = target.path, state = ImageState.QUARANTINED, embedding = embedding)
                            if (existing == null) dao.insert(entry) else dao.update(entry)
                            labels += Label(originalPath, embedding, junk = true)
                            moved++
                        }
                    }
                }
            }
        }
        db.labels().upsert(labels)
        status.value = getString(R.string.share_training)
        Scoring.get(this).rescoreAll()

        return buildString {
            append(getString(R.string.msg_quarantined, moved))
            if (excluded > 0) append(", ").append(getString(R.string.share_excluded, excluded))
            if (failed > 0) append(", ").append(getString(R.string.msg_failed, failed))
        }
    }

    private fun resolveFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)
        return runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let(::File) else null
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
