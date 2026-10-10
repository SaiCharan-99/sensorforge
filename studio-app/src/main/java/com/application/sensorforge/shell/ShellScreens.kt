package com.application.sensorforge.shell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.sdk.forge.UseCaseDef
import com.application.sensorforge.sdk.models.ModelStatus
import com.application.sensorforge.sdk.runtime.BackendLog
import com.application.sensorforge.sdk.runtime.CustomUseCases
import com.application.sensorforge.sdk.runtime.ModelRegistry
import com.application.sensorforge.sdk.runtime.Offline
import com.application.sensorforge.sdk.runtime.SessionLog
import com.application.sensorforge.ui.InfoCard
import com.application.sensorforge.ui.Mono
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SectionTitle
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.ui.theme.TextSecondary
import java.io.File

@Composable
private fun StatusFor(s: ModelStatus) {
    val c = SfTheme.colors
    when (s) {
        ModelStatus.INSTALLED, ModelStatus.BUILTIN, ModelStatus.TRAINED -> StatusChip(s.name.lowercase(), c.pass, "✓")
        ModelStatus.MISSING, ModelStatus.NOT_TRAINED, ModelStatus.LAPTOP_ONLY -> StatusChip(s.name.lowercase().replace('_', ' '), c.unverified, "!")
        ModelStatus.HASH_MISMATCH -> StatusChip("hash mismatch", c.fail, "✕")
        ModelStatus.OMITTED, ModelStatus.UNAVAILABLE -> StatusChip(s.name.lowercase(), c.pending, "–")
    }
}

/** S17: every model-stack slot with its live status, plus measured backends from the backend log. */
@Composable
fun BackendLogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val records by BackendLog.records.collectAsState()
    val states = remember(records.size) { ModelRegistry.get(ctx).states() }
    val sums = remember(records.size) { BackendLog.summaries() }
    val airplane = Offline.airplaneOn(ctx)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("‹ Home", onClick = onBack)
        Text("Backend log", style = MaterialTheme.typography.headlineLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (airplane) StatusChip("Airplane mode on", SfTheme.colors.info, "✈") else StatusChip("Airplane mode off", SfTheme.colors.pending, "·")
            val offlineRuns = records.count { it.offline }
            if (offlineRuns > 0) StatusChip("$offlineRuns run(s) measured offline", SfTheme.colors.pass, "✓")
        }
        InfoCard("Nothing here claims a backend unless a run was logged. Models without a record show their status only.")
        SectionTitle("Measured runs")
        if (sums.isEmpty()) SfCard { Text("No model runs logged yet. Run PackCheck or SealSafe once to measure the detector.", style = MaterialTheme.typography.bodyMedium) }
        sums.forEach { s ->
            SfCard {
                Text(s.model, style = MaterialTheme.typography.titleMedium)
                Mono("${s.runtime} · ${s.provider} · ${s.backend}")
                Text("input ${s.input}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Mono("load ${s.loadMs?.let { "%.0f ms".format(it) } ?: "–"} · cold ${s.coldMs?.let { "%.0f ms".format(it) } ?: "–"} · warm p50 ${s.warmP50?.let { "%.1f".format(it) } ?: "–"} / p95 ${s.warmP95?.let { "%.1f".format(it) } ?: "–"} ms")
                Text("${s.runs} record(s), ${s.offlineRuns} offline", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        SectionTitle("Model stack")
        states.forEach { st ->
            SfCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusFor(st.status)
                    Text("${st.entry.slot} · ${st.entry.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Mono("${st.entry.runtime} · expect ${st.entry.expectedBackend}")
                Text("licence ${st.entry.licence} · ${st.entry.bytes?.let { "${it / 1024} KB" } ?: "built in"}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                st.sha256?.let { Mono("sha256 ${it.take(16)}…") }
                if (!st.usable) Text("Fallback: ${st.entry.fallbackClaim}", style = MaterialTheme.typography.bodyMedium, color = SfTheme.colors.unverified.content)
                if (st.note.isNotBlank()) Text(st.note, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        SecondaryButton("Clear backend log", enabled = records.isNotEmpty()) { BackendLog.clear() }
    }
}

/** S18: what each sensor may do, retention, and delete-all. Permission state is the real Android state. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf<String?>(null) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val sessionFiles = remember(deleted) { SessionLog.files(ctx) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("‹ Home", onClick = onBack)
        Text("Settings & privacy", style = MaterialTheme.typography.headlineLarge)
        InfoCard("Everything runs on this phone. No network calls in any core flow; nothing is uploaded.")
        SectionTitle("Sensors")
        SfCard {
            SensorRow("Camera", "Used only while a verify screen or capture is open and visible. Never in the background.", granted(Manifest.permission.CAMERA))
            SensorRow("Location", "CrashGuard evidence only, while armed and visible; GPS plus cached fixes, no network.", granted(Manifest.permission.ACCESS_FINE_LOCATION))
            SensorRow("Motion sensors", "Accelerometer, gyroscope, rotation vector. Used while CrashGuard is armed and visible.", true, "no permission needed")
            SensorRow("Microphone", "Not used. The app does not request it.", false, "never requested")
            SecondaryButton("Open system permissions") {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        SectionTitle("Data on this phone")
        SfCard {
            Text("Retention", style = MaterialTheme.typography.titleMedium)
            Text("Evidence frames, receipts, examples, heads and packs stay in app-private storage until you delete them. CrashGuard incident evidence is encrypted with an Android Keystore key.", style = MaterialTheme.typography.bodyMedium)
            Mono("session logs: ${sessionFiles.size} file(s), ${sessionFiles.sumOf { it.length() } / 1024} KB")
            Text("Face blur in stored evidence: off. No face-detection model is installed, so evidence frames of people are not blurred; use controlled props.", style = MaterialTheme.typography.bodyMedium, color = SfTheme.colors.unverified.content)
            deleted?.let { InfoCard(it) }
            SecondaryButton("Delete all SensorForge data") { confirm = true }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete everything?") },
        text = { Text("Removes forge projects, examples, trained heads, packs, recordings, evidence, receipts and logs on this phone. Bundled models and specs stay.") },
        confirmButton = {
            TextButton({
                confirm = false
                var n = 0
                for (name in listOf("forge", "heads", "verify", "crashguard", "logs", "tags")) {
                    val dir = File(ctx.filesDir, name)
                    n += dir.walkBottomUp().count()
                    dir.deleteRecursively()
                }
                File(ctx.cacheDir, "import").deleteRecursively()
                BackendLog.clear()
                deleted = "Deleted $n item(s). Restart the app to reset in-memory state."
                onDeleted()
            }) { Text("Delete", color = SfTheme.colors.failSolid) }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } },
    )
}

@Composable
private fun SensorRow(name: String, what: String, on: Boolean, label: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(what, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        if (on) StatusChip(label ?: "allowed", SfTheme.colors.pass, "✓") else StatusChip(label ?: "not granted", SfTheme.colors.pending, "–")
    }
}

/** "Forge new verifier": name + sentence gives a new use case with the same guided flow. */
@Composable
fun NewVerifierDialog(onDismiss: () -> Unit, onCreated: (UseCaseDef) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var sentence by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forge new verifier") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    sentence, { sentence = it }, label = { Text("Describe the procedure") }, minLines = 3,
                    supportingText = { Text("e.g. Open the box. Remove the charger from the box. Never show impostor cable.") },
                )
                error?.let { Text(it, color = SfTheme.colors.failSolid, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton({
                when (val r = SensorForge.createUseCase(ctx, title, sentence)) {
                    is CustomUseCases.Created.Ok -> onCreated(r.def)
                    is CustomUseCases.Created.Rejected -> error = r.reason
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
