package de.drepp.picturecleaner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import de.drepp.picturecleaner.data.ImageEntry
import java.io.File

@Composable
fun ProgressBlock(progress: Progress) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(if (progress.total > 0) "${progress.label}: ${progress.done} из ${progress.total}" else "Подготовка…")
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
fun ScanScreen(
    summary: ScanSummary,
    model: ModelInfo,
    threshold: Float,
    includeDocuments: Boolean,
    busy: Boolean,
    onThreshold: (Float) -> Unit,
    onIncludeDocuments: (Boolean) -> Unit,
    onScan: () -> Unit,
    onMove: () -> Unit,
) {
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Папки: WhatsApp Images (без закрытых чатов и отправленных), Viber, Telegram, ChatGPT", style = MaterialTheme.typography.bodySmall)
        Text("Проверено картинок: ${summary.scored}")
        if (summary.kept > 0) Text("Оставлено после просмотра: ${summary.kept}", style = MaterialTheme.typography.bodySmall)
        if (summary.missingEmbeddings > 0) {
            Text(
                "${summary.missingEmbeddings} картинок проверены старой версией — нажми «Проверить», чтобы модель могла на них учиться.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Проверить картинки")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Скриншоты, флаеры, меню, документы — тоже мусор", modifier = Modifier.weight(1f))
            Switch(checked = includeDocuments, onCheckedChange = onIncludeDocuments, enabled = !busy)
        }
        Text(
            if (model.junk + model.keep == 0) "Модель пока не обучена: учится на том, что ты удаляешь и возвращаешь."
            else "Обучение: удалено ${model.junk}, оставлено ${model.keep}. Вес твоих решений: ${(model.personalWeight * 100).toInt()}%" +
                if (model.personalWeight == 0f) " (нужно хотя бы по 10 каждого вида)" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Порог: %.2f — чем выше, тем меньше настоящих фото попадёт в карантин".format(threshold))
        Slider(value = threshold, onValueChange = onThreshold, valueRange = 0.2f..0.95f, enabled = !busy)
        Text(
            "Кандидатов: ${summary.candidates} (${formatBytes(summary.candidateBytes)})",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = onMove, enabled = !busy && summary.candidates > 0, modifier = Modifier.fillMaxWidth()) {
            Text("Перенести ${summary.candidates} в карантин")
        }
        Text(
            "Карантин — альбом Pictures/PictureCleaner. Там можно вернуть лишнее, а остальное удалить разом.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuarantineScreen(
    items: List<ImageEntry>,
    selected: Set<Long>,
    busy: Boolean,
    onToggle: (Long) -> Unit,
    onRestoreSelected: () -> Unit,
    onRestoreOne: (Long) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var preview by remember { mutableStateOf<ImageEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val totalBytes = items.sumOf { it.sizeBytes }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("${items.size} картинок, ${formatBytes(totalBytes)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Сверху — самые сомнительные. Тап — отметить для возврата, долгое нажатие — посмотреть.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onRestoreSelected, enabled = !busy && selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Вернуть (${selected.size})")
                }
                Button(
                    onClick = { confirmDelete = true },
                    enabled = !busy && items.isNotEmpty() && selected.isEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Удалить все")
                }
            }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { entry ->
                val isSelected = entry.id in selected
                Box(
                    Modifier
                        .padding(2.dp)
                        .aspectRatio(1f)
                        .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
                        .combinedClickable(onClick = { onToggle(entry.id) }, onLongClick = { preview = entry }),
                ) {
                    AsyncImage(
                        model = File(entry.currentPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        "%.2f".format(entry.score),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x88000000)).padding(horizontal = 3.dp),
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.White, MaterialTheme.shapes.extraLarge),
                        )
                    }
                }
            }
        }
    }

    preview?.let { entry ->
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = File(entry.currentPath),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Text(
                    File(entry.originalPath).parentFile?.name.orEmpty() + " · " + formatBytes(entry.sizeBytes),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { preview = null }, modifier = Modifier.weight(1f)) {
                        Text("Закрыть", color = Color.White)
                    }
                    Button(onClick = { onRestoreOne(entry.id); preview = null }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Вернуть на место")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить ${items.size} картинок?") },
            text = { Text("Освободится ${formatBytes(totalBytes)}. Файлы удаляются насовсем, без корзины.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAll() }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Нужен доступ ко всем файлам", style = MaterialTheme.typography.titleLarge)
        Text(
            "Картинки WhatsApp лежат в Android/media, переносить и удалять их можно только с этим разрешением. " +
                "Приложение работает офлайн и ничего никуда не отправляет.",
        )
        Button(onClick = onGrant) { Text("Открыть настройки") }
    }
}
