package com.autodoomscroller.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autodoomscroller.AppGraph
import com.autodoomscroller.export.Exporter
import com.autodoomscroller.service.ReelsAccessibilityService
import com.autodoomscroller.session.SessionController
import com.autodoomscroller.session.SessionMode
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serviceEnabled by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        serviceEnabled = isReelsServiceEnabled(context)
        onPauseOrDispose { }
    }
    val mode by SessionController.mode.collectAsStateWithLifecycle()
    val researchMode by SessionController.researchMode.collectAsStateWithLifecycle()
    val adSkip by SessionController.adSkip.collectAsStateWithLifecycle()
    val labeledCounts by AppGraph.reelRepository.observeLabeledCounts()
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("auto_doomscroller", style = MaterialTheme.typography.headlineMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Accessibility service", style = MaterialTheme.typography.titleMedium)
                    Text(if (serviceEnabled) "Enabled" else "Not enabled. Turn it on to start.")
                    if (!serviceEnabled) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("Open accessibility settings") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    ModeOption("Off", SessionMode.OFF, mode, enabled = true)
                    ModeOption("Learning: watch normally, it learns", SessionMode.LEARNING, mode, serviceEnabled)
                    // TODO: enable once PreferenceModel is trained (300+ labeled reels and the
                    //  validation precision target is met).
                    ModeOption("Auto: skips what you would skip", SessionMode.AUTO, mode, enabled = false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Skip ads")
                            Text(
                                "Swipes past ads as soon as they appear",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = adSkip, onCheckedChange = { SessionController.setAdSkip(it) })
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Data", style = MaterialTheme.typography.titleMedium)
                    Text("Labeled: ${labeledCounts.values.sum()}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Instagram Reels ${labeledCounts[INSTAGRAM] ?: 0} · YouTube Shorts ${labeledCounts[YOUTUBE] ?: 0}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Save thumbnails (research mode)", Modifier.weight(1f))
                        Switch(
                            checked = researchMode,
                            onCheckedChange = { SessionController.setResearchMode(it) },
                        )
                    }
                    Row {
                        Button(onClick = {
                            scope.launch {
                                val dir = Exporter.export(context)
                                Toast.makeText(context, "Exported to ${dir.path}", Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Export") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            AppGraph.appScope.launch {
                                AppGraph.reelRepository.deleteAll()
                                AppGraph.thumbnailStore.deleteAll()
                            }
                        }) { Text("Delete all data") }
                    }
                }
            }
        }
    }
}

private const val INSTAGRAM = "com.instagram.android"
private const val YOUTUBE = "com.google.android.youtube"

@Composable
private fun ModeOption(label: String, value: SessionMode, current: SessionMode, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = current == value,
            onClick = { SessionController.setMode(value) },
            enabled = enabled,
        )
        Text(label)
    }
}

private fun isReelsServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, ReelsAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}
