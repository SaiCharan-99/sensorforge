package com.application.sensorforge.helloforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

// Placeholder until the pack loader exists (Phase 4): then this becomes the two-line import.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("HelloForge: no pack loaded") }
    }
}
