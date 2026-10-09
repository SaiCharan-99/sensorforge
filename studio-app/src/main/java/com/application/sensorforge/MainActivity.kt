package com.application.sensorforge

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.sensorforge.crashguard.CrashGuardViewModel
import com.application.sensorforge.crashguard.ModelCard
import com.application.sensorforge.ui.SfCard
import com.application.sensorforge.ui.theme.SensorforgeTheme

class MainActivity : ComponentActivity() {
    private val crashGuard: CrashGuardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SensorforgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background) { p ->
                    val model by crashGuard.modelUi.collectAsState()
                    val live by crashGuard.live.collectAsState()
                    Column(
                        Modifier.padding(p).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("CrashGuard", style = MaterialTheme.typography.headlineLarge)
                        SfCard {
                            Text("Motion sensors only, camera and mic off", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "LIVE  accel %.0f Hz · gyro %.0f Hz · rotation %.0f Hz".format(live.accelHz, live.gyroHz, live.rotHz),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (live.missing.isNotEmpty()) Text("CapabilityMissing: ${live.missing}")
                        }
                        ModelCard(model, crashGuard)
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); crashGuard.onVisible() }
    override fun onPause() { crashGuard.onHidden(); super.onPause() }
}
