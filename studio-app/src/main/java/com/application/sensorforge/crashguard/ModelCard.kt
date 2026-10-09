package com.application.sensorforge.crashguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.sensorforge.sdk.train.ClassMetrics
import com.application.sensorforge.ui.HashCard
import com.application.sensorforge.ui.LossCurveChart
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.SimulationChip
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.theme.MonoStyle
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.ui.theme.TextSecondary

private val HINTS = mapOf(
    "normal" to "Hold, carry or walk with the phone normally.",
    "shake" to "Shake the phone hard in your hand.",
    "drop" to "Drop the phone onto a bed or sofa, then leave it still.",
)

/** S10 IMU risk model card: data, Train, loss curve, HashCard, held-out metrics, promote/rollback. */
@Composable
fun ModelCard(ui: ModelUi, vm: CrashGuardViewModel) {
    SfCard {
        Text("IMU risk model (H3)", style = MaterialTheme.typography.titleMedium)
        Text("Classes: normal · shake · drop · impact_like", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)

        ui.counts.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.label, style = MonoStyle, modifier = Modifier.fillMaxWidth(0.35f))
                Text("dev ${c.dev} · held-out ${c.heldOut} · ${c.sessions} sessions", style = MaterialTheme.typography.bodyMedium)
            }
            if (c.dev < 5 && !c.simulation) Text("  needs ≥ 5 dev windows", color = SfTheme.colors.unverified.content, style = MaterialTheme.typography.bodyMedium)
        }
        SimulationChip("impact_like comes from 12 synthetic replays")

        val rec = ui.recorder
        if (rec != null) {
            StatusChip("recording ${rec.label}: ${rec.remainingS} s", SfTheme.colors.info, "●")
            Text(HINTS.getValue(rec.label), style = MaterialTheme.typography.bodyLarge)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("normal", "shake", "drop").forEach { l ->
                    SecondaryButton(l, enabled = !ui.training) { vm.record(l) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ui.heldOutNext, vm::setHeldOut)
                Text("Next recording is held-out (never used for training)", style = MaterialTheme.typography.bodyMedium)
            }
        }

        PrimaryButton(if (ui.training) "Training…" else "Train", enabled = !ui.training && rec == null) { vm.train() }
        ui.error?.let { Text(it, color = SfTheme.colors.fail.content, style = MaterialTheme.typography.bodyMedium) }

        ui.latest?.let { r ->
            Text("Version v${r.headVersion} · ${r.trainMillis} ms on ${r.trainingBackend} · best epoch ${r.bestEpoch}", style = MaterialTheme.typography.bodyMedium)
            Text("train ${r.trainSamples} · val ${r.valSamples} · held-out ${r.heldOutSamples} · changed ${r.changedParameterCount}/${r.parameterCount} params", style = MaterialTheme.typography.bodyMedium)
            LossCurveChart(r.lossCurve, r.valLossCurve)
            HashCard("Weights sha256 (parent → trained)", r.parentSha256, r.trainedWeightsSha256)
            Text("Held-out recall / precision", style = MaterialTheme.typography.titleMedium)
            MetricsTable(r.heldOutMetrics)
            Text("dataset ${r.datasetVersion}", style = MonoStyle, color = TextSecondary)
        }

        if (ui.versions.isNotEmpty()) {
            Text("Active: ${ui.active?.let { "v$it" } ?: "none (threshold mode used)"}", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val newest = ui.versions.last()
                if (ui.active != newest) SecondaryButton("Approve v$newest") { vm.promote(newest) }
                val prev = ui.versions.filter { it < (ui.active ?: 0) }.maxOrNull()
                if (prev != null) SecondaryButton("Rollback to v$prev") { vm.promote(prev) }
            }
        }
        var confirmDelete by remember { mutableStateOf(false) }
        SecondaryButton("Delete all recordings") { confirmDelete = true }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all recordings?") },
            text = { Text("Removes every live recording on this phone. Trained head versions are kept.") },
            confirmButton = {
                TextButton({ confirmDelete = false; vm.deleteAllRecordings() }) { Text("Delete", color = SfTheme.colors.failSolid) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun MetricsTable(m: Map<String, ClassMetrics>) = Column {
    m.forEach { (c, v) ->
        Row {
            Text(c, style = MonoStyle, modifier = Modifier.fillMaxWidth(0.35f))
            Text(
                if (v.support == 0) "no held-out data" else "%.2f / %.2f  (n=%d)".format(v.recall, v.precision, v.support),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
