package com.application.sensorforge.crashguard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.application.sensorforge.sdk.detect.IncidentState
import com.application.sensorforge.sdk.detect.IncidentState.*
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.SimulationChip
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.shortHash
import com.application.sensorforge.ui.theme.CountdownStyle
import com.application.sensorforge.ui.theme.MonoStyle
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.ui.theme.TextPrimary
import com.application.sensorforge.ui.theme.TextSecondary

val INCIDENT_SCREENS = setOf(COUNTDOWN, CANCELLED, CONFIRMED, TIMEOUT, EVIDENCE_SAVED, SOS_SIMULATED)

/** S10 top: arm toggle, status, live |a| / |ω| trace, replay button. */
@Composable
fun ArmedCard(live: LiveUi, inc: IncidentUi, vm: CrashGuardViewModel) {
    val armed = inc.state == ARMED || inc.state == CANDIDATE
    SfCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Motion Watch", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (armed) "ARMED: motion sensors only, camera and mic off" else "DISARMED",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                )
            }
            Switch(armed, { if (it) vm.arm() else vm.disarm() })
        }
        if (inc.state == CANDIDATE) StatusChip("checking possible event", SfTheme.colors.info, "●")
        Text(
            "LIVE  accel %.0f Hz · gyro %.0f Hz · rotation %.0f Hz".format(live.accelHz, live.gyroHz, live.rotHz),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (live.missing.isNotEmpty()) Text("CapabilityMissing: ${live.missing}", color = SfTheme.colors.fail.content)
        live.replaying?.let { SimulationChip("replaying $it") }
        Trace(live.accelG, live.gyroDps, vm.spec.gate.accelPeakG.toFloat())
        val last = inc.trace.lastOrNull()
        if (last != null) Text(last.message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        SecondaryButton(
            "Play impact replay (SIMULATION)", enabled = armed && live.replaying == null,
            modifier = Modifier.fillMaxWidth(),
        ) { vm.playReplay(vm.model.replays.indices.random()) }
    }
}

/** |a| in g (chart1, left scale 0..8 g) and |ω| in dps (chart2, right scale 0..800 dps), last 5 s. */
@Composable
private fun Trace(a: List<Float>, w: List<Float>, gateG: Float) {
    val c = SfTheme.colors
    Column {
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            fun y(v: Float, max: Float) = size.height * (1 - (v / max).coerceIn(0f, 1f))
            drawLine(c.chartGrid, Offset(0f, y(gateG, 8f)), Offset(size.width, y(gateG, 8f)), 1.dp.toPx())
            fun series(v: List<Float>, max: Float, color: Color) {
                if (v.size < 2) return
                val p = Path()
                v.forEachIndexed { i, x -> val px = size.width * i / (v.size - 1); if (i == 0) p.moveTo(px, y(x, max)) else p.lineTo(px, y(x, max)) }
                drawPath(p, color, style = Stroke(2.dp.toPx()))
            }
            series(a, 8f, c.chart1)
            series(w, 800f, c.chart2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(c.chart1, "|a| g (0–8)")
            Legend(c.chart2, "|ω| dps (0–800)")
            Legend(c.chartGrid, "gate %.0f g".format(gateG))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(12.dp, 2.dp).background(color))
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium, color = SfTheme.colors.chartAxis)
}

/** S11: "Possible impact detected", countdown, oversized Cancel, "I need help". */
@Composable
fun CountdownScreen(inc: IncidentUi, vm: CrashGuardViewModel) {
    Column(
        Modifier.fillMaxSize().background(SfTheme.colors.fail.container).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (inc.simulation) SimulationChip("triggered by an impact replay")
        Spacer(Modifier.weight(1f))
        Text("Possible impact detected", style = MaterialTheme.typography.headlineLarge, color = TextPrimary, textAlign = TextAlign.Center)
        Text(
            "${inc.countdownLeftS}", style = CountdownStyle, color = TextPrimary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text("Simulated SOS options appear when this reaches 0. Nothing is sent.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Button(
            vm::cancel, Modifier.fillMaxWidth().heightIn(min = 96.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("Cancel, I'm OK", style = MaterialTheme.typography.headlineMedium) }
        Button(
            vm::needHelp, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SfTheme.colors.failSolid),
        ) { Text("I need help", style = MaterialTheme.typography.labelLarge) }
    }
}

private val LIFECYCLE = listOf(DISARMED, ARMED, CANDIDATE, SUSPECTED, COUNTDOWN, CANCELLED, CONFIRMED, TIMEOUT, EVIDENCE_SAVED, SOS_SIMULATED, RESOLVED)

/** S12: lifecycle timeline, SIMULATED SOS banner, evidence, decision trace, receipt. */
@Composable
fun EvidenceScreen(inc: IncidentUi, vm: CrashGuardViewModel) {
    val reached = inc.trace.map { it.state }.toSet()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (inc.simulation) SimulationChip("incident triggered by an impact replay")
        Text("CrashGuard incident", style = MaterialTheme.typography.headlineLarge)
        when (inc.state) {
            SOS_SIMULATED -> Banner("SIMULATED SOS", "MockIncidentTransport: no message, call or SMS was made.")
            CANCELLED -> StatusChip("cancelled: audited, no alert", SfTheme.colors.pending, "✕")
            else -> StatusChip(inc.state.name.lowercase().replace('_', ' '), SfTheme.colors.unverified, "!")
        }
        SfCard {
            Text("Lifecycle", style = MaterialTheme.typography.titleMedium)
            LIFECYCLE.forEach { s ->
                val on = s in reached
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s == inc.state) "●" else if (on) "✓" else "○", color = if (on) SfTheme.colors.pass.content else TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(s.name, style = MonoStyle, color = if (on) TextPrimary else TextSecondary)
                }
            }
        }
        inc.evidence?.let { e ->
            SfCard {
                Text("Evidence", style = MaterialTheme.typography.titleMedium)
                Text("Frozen motion trace: ${e.imuSamples} samples (sha256 ${shortHash(e.imuSha256)})", style = MaterialTheme.typography.bodyMedium)
                Text("Peak %.1f g · post-peak std %.3f g".format(e.peakG, e.postPeakStdG), style = MaterialTheme.typography.bodyMedium)
                Text("Decided by: ${e.decidedBy}" + (e.impactProb?.let { " · p(impact_like) %.2f".format(it) } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(
                    e.location?.let { "Location %.5f, %.5f ±%.0f m, %d s old".format(it.lat, it.lon, it.accuracyM, it.ageMs / 1000) }
                        ?: "Location: ${e.locationStatus}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Evidence receipt", style = MaterialTheme.typography.titleMedium)
                Text(e.receiptSha256, style = MonoStyle)
                inc.evidenceFile?.let { Text("Stored encrypted (Keystore AES-GCM): $it", style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
            }
        }
        SfCard {
            Text("Decision trace", style = MaterialTheme.typography.titleMedium)
            inc.trace.forEach { t -> Text("${t.state}  ${t.message}", style = MaterialTheme.typography.bodyMedium) }
        }
        if (inc.state == EVIDENCE_SAVED) PrimaryButton("Send simulated SOS") { vm.sendSimulatedSos() }
        if (inc.state in setOf(CANCELLED, EVIDENCE_SAVED, SOS_SIMULATED)) SecondaryButton("Resolve and re-arm", modifier = Modifier.fillMaxWidth()) { vm.resolve() }
    }
}

/** SIMULATION colours, never red-solid (design.md: must not look like a real alert being sent). */
@Composable
private fun Banner(title: String, sub: String) {
    val c = SfTheme.colors.simulation
    Column(
        Modifier.fillMaxWidth().background(c.container, RoundedCornerShape(20.dp)).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("▶ $title", style = MaterialTheme.typography.headlineMedium, color = c.content)
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = c.content)
    }
}

fun IncidentState.isIncidentScreen() = this in INCIDENT_SCREENS
