package de.drepp.picturecleaner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.drepp.picturecleaner.ui.MainViewModel
import de.drepp.picturecleaner.ui.PermissionScreen
import de.drepp.picturecleaner.ui.ProgressBlock
import de.drepp.picturecleaner.ui.QuarantineScreen
import de.drepp.picturecleaner.ui.ScanScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val hasAccess = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicLightColorScheme(LocalContext.current)) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (hasAccess.value) App(vm) else PermissionScreen(::openAccessSettings)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess.value = Environment.isExternalStorageManager()
        if (hasAccess.value) vm.syncQuarantine()
    }

    private fun openAccessSettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
        )
    }
}

@androidx.compose.runtime.Composable
private fun App(vm: MainViewModel) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val quarantined by vm.quarantined.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val busy = progress != null

    LocalView.current.keepScreenOn = busy

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Поиск") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Карантин (${quarantined.size})") })
            }
            progress?.let { ProgressBlock(it) }
            when (tab) {
                0 -> ScanScreen(
                    summary = summary,
                    threshold = threshold,
                    busy = busy,
                    onThreshold = { vm.threshold.value = it },
                    onScan = vm::scan,
                    onMove = { vm.moveCandidates(); tab = 1 },
                )
                else -> QuarantineScreen(
                    items = quarantined,
                    selected = selected,
                    busy = busy,
                    onToggle = vm::toggle,
                    onRestoreSelected = vm::restoreSelected,
                    onRestoreOne = { id -> vm.selected.value = setOf(id); vm.restoreSelected() },
                    onDeleteAll = vm::deleteAllQuarantined,
                )
            }
        }
    }
}
