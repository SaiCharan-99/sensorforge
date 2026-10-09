package com.application.sensorforge

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.sensorforge.sdk.detect.ImuChannel
import com.application.sensorforge.sdk.detect.ImuRingBuffer
import com.application.sensorforge.sdk.sensors.AndroidImuSource
import com.application.sensorforge.ui.theme.SensorforgeTheme
import kotlinx.coroutines.delay

/** Phase 2 slice 1: live IMU capture check (Motion Watch only, foreground). */
class MainActivity : ComponentActivity() {
    private val buffer = ImuRingBuffer(20.0)
    private lateinit var imu: AndroidImuSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imu = AndroidImuSource(this)
        enableEdgeToEdge()
        setContent {
            SensorforgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { p ->
                    ImuCheck(buffer, imu.missingChannels, Modifier.padding(p).padding(16.dp))
                }
            }
        }
    }

    override fun onResume() { super.onResume(); imu.start(buffer::add) }
    override fun onPause() { imu.stop(); buffer.clear(); super.onPause() }
}

@Composable
fun ImuCheck(buffer: ImuRingBuffer, missing: List<ImuChannel>, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val rates = ImuChannel.entries.joinToString("  ") { "%s %.1f Hz".format(it, buffer.rateHz(it)) }
            val line = "LIVE  $rates  buffer %.1f s / %d samples".format(buffer.spanSeconds(), buffer.size())
            Log.i("SF.IMU", line)
            text = line
        }
    }
    Column(modifier) {
        Text("Motion Watch: IMU check", style = MaterialTheme.typography.titleLarge)
        Text(text)
        if (missing.isNotEmpty()) Text("Missing sensors: $missing")
    }
}
