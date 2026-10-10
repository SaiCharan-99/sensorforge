package com.application.sensorforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.sensorforge.crashguard.ArmedCard
import com.application.sensorforge.crashguard.CountdownScreen
import com.application.sensorforge.crashguard.CrashGuardViewModel
import com.application.sensorforge.crashguard.DetectionCard
import com.application.sensorforge.crashguard.EvidenceScreen
import com.application.sensorforge.crashguard.isIncidentScreen
import com.application.sensorforge.forge.ForgeFlow
import com.application.sensorforge.forge.ForgeViewModel
import com.application.sensorforge.packcheck.PackCheckScreen
import com.application.sensorforge.packcheck.PackCheckViewModel
import com.application.sensorforge.packcheck.SaveAs
import com.application.sensorforge.perception.CameraCheckCard
import com.application.sensorforge.sdk.detect.IncidentState
import com.application.sensorforge.sdk.forge.UseCaseDef
import com.application.sensorforge.sdk.SensorForge
import com.application.sensorforge.shell.BackendLogScreen
import com.application.sensorforge.shell.NewVerifierDialog
import com.application.sensorforge.shell.SettingsScreen
import com.application.sensorforge.ui.PrimaryButton
import com.application.sensorforge.ui.SecondaryButton
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.StatusChip
import com.application.sensorforge.ui.theme.SensorforgeTheme
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    private val crashGuard: CrashGuardViewModel by viewModels()
    private val repRight: com.application.sensorforge.repright.RepRightViewModel by viewModels()
    private val scene: com.application.sensorforge.scene.SceneViewModel by viewModels()
    private val handover: com.application.sensorforge.handover.HandoverViewModel by viewModels()
    private val packCheck: PackCheckViewModel by viewModels()
    private val forge: ForgeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.application.sensorforge.sdk.runtime.SessionLog.init(this)
        com.application.sensorforge.sdk.runtime.BackendLog.init(this)
        setContent {
            SensorforgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background) { p ->
                    val inc by crashGuard.incident.collectAsState()
                    // Routes: home | uc:<id> | forge:<id> | run:<id> | run:<id>:<planItemId> | camera
                    var route by rememberSaveable { mutableStateOf("home") }
                    Column(Modifier.padding(p)) {
                        when {
                            inc.state == IncidentState.COUNTDOWN -> CountdownScreen(inc, crashGuard)
                            inc.state.isIncidentScreen() -> EvidenceScreen(inc, crashGuard)
                            else -> Router(route) { route = it }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Router(route: String, go: (String) -> Unit) {
        val parts = route.split(':')
        val id = parts.getOrNull(1)
        BackHandler(enabled = route != "home") {
            go(when (parts[0]) { "forge", "run", "camera" -> if (id != null && parts[0] != "camera") "uc:$id" else "home"; else -> "home" })
        }
        when (parts[0]) {
            "uc" -> UseCaseScreen(SensorForge.useCase(this@MainActivity, id!!), go)
            "forge" -> {
                LaunchedEffect(id) { forge.open(id!!) }
                ForgeFlow(
                    forge, crashGuard,
                    onRun = { item -> go("run:$id" + (item?.let { ":$it" } ?: "")) },
                    onBack = { go("uc:$id") },
                )
            }
            "run" -> RunScreen(id!!, parts.getOrNull(2), go)
            "repright" -> com.application.sensorforge.repright.RepRightScreen(repRight) { go("home") }
            "scene" -> com.application.sensorforge.scene.SceneScreen(scene) { go("home") }
            "handover" -> com.application.sensorforge.handover.HandoverScreen(handover) { go("home") }
            "backend" -> BackendLogScreen { go("home") }
            "settings" -> SettingsScreen({ go("home") }, { forge.close() })
            "camera" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("‹ Home") { go("home") }
                CameraCheckCard()
            }
            else -> HomeScreen(go)
        }
    }

    @Composable
    private fun HomeScreen(go: (String) -> Unit) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SensorForge", style = MaterialTheme.typography.headlineLarge)
            Text("The AI sees. The code decides. The receipt proves it.", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            var showNew by remember { mutableStateOf(false) }
            SensorForge.useCases(this@MainActivity).forEach { def ->
                SfCard {
                    Text(def.title, style = MaterialTheme.typography.titleMedium)
                    Text(def.blurb, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    StatusChip(if (def.mode == "detect") "Detect · sensors" else "Verify · camera", SfTheme.colors.info, if (def.mode == "detect") "∿" else "◉")
                    PrimaryButton("Open ${def.title}") { go("uc:${def.id}") }
                }
            }
            SfCard {
                Text("SceneCheck", style = MaterialTheme.typography.titleMedium)
                Text("Boxes every object it sees, you name them, it trains on this phone, then judges what is inside or outside the box.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                StatusChip("Verify · camera (learned)", SfTheme.colors.info, "◉")
                PrimaryButton("Open SceneCheck") { go("scene") }
            }
            SfCard {
                Text("RepRight", style = MaterialTheme.typography.titleMedium)
                Text("Counts a physio or yoga repetition only when the configured angle, hold and tempo are met.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                StatusChip("Verify · camera (pose)", SfTheme.colors.info, "◉")
                PrimaryButton("Open RepRight") { go("repright") }
            }
            SfCard {
                Text("HandoverProof", style = MaterialTheme.typography.titleMedium)
                Text("Checks each item was handed from the partner side to the customer side, the signed slip was read and the steps walked.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                StatusChip("Verify · camera (hands), steps, location", SfTheme.colors.info, "◉")
                PrimaryButton("Open HandoverProof") { go("handover") }
            }
            PrimaryButton("Forge new verifier") { showNew = true }
            SecondaryButton("Backend log") { go("backend") }
            SecondaryButton("Settings & privacy") { go("settings") }
            SecondaryButton("Camera diagnostics") { go("camera") }
            if (showNew) NewVerifierDialog({ showNew = false }) { def -> showNew = false; go("forge:${def.id}") }
        }
    }

    @Composable
    private fun UseCaseScreen(def: UseCaseDef, go: (String) -> Unit) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("‹ Home") { go("home") }
            Text(def.title, style = MaterialTheme.typography.headlineLarge)
            Text(def.blurb, style = MaterialTheme.typography.bodyLarge)
            SfCard {
                Text("Guided setup", style = MaterialTheme.typography.titleMedium)
                Text("Describe → Plan → Capture → Train → Test → Export → Import", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                PrimaryButton("Set up ${def.title}") { go("forge:${def.id}") }
            }
            SfCard {
                Text("Run", style = MaterialTheme.typography.titleMedium)
                Text(if (def.mode == "detect") "Arm CrashGuard, calibrate, replay." else "Verify live with the camera using the active spec or your latest exported pack.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                PrimaryButton("Run ${def.title}") { go("run:${def.id}") }
            }
        }
    }

    @Composable
    private fun RunScreen(id: String, itemId: String?, go: (String) -> Unit) {
        val def = SensorForge.useCase(this@MainActivity, id)
        Column {
            SecondaryButton("‹ ${def.title}", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { go(if (itemId != null) "forge:$id" else "uc:$id") }
            if (def.mode == "detect") {
                val model by crashGuard.modelUi.collectAsState()
                val live by crashGuard.live.collectAsState()
                val inc by crashGuard.incident.collectAsState()
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CrashGuard", style = MaterialTheme.typography.headlineLarge)
                    ArmedCard(live, inc, crashGuard)
                    DetectionCard(inc, model, crashGuard)
                }
            } else {
                val pc by packCheck.ui.collectAsState()
                LaunchedEffect(id, itemId) { forge.open(id); packCheck.configure(forge.runTarget(), def.title, forge.runParams()) }
                val title = forge.ui.value.plan?.items?.firstOrNull { it.id == itemId }?.title
                val save = if (itemId != null && title != null) SaveAs(title) { r -> forge.saveLive(itemId, r.timeline) } else null
                PackCheckScreen(packCheck, pc, save)
            }
        }
    }

    override fun onResume() { super.onResume(); crashGuard.onVisible() }
    override fun onPause() { crashGuard.onHidden(); packCheck.onHidden(); handover.stop(); repRight.stop(); scene.stopCamera(); super.onPause() }
}
