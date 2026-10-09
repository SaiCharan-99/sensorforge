package com.application.sensorforge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.application.sensorforge.crashguard.IncidentLog
import com.application.sensorforge.sdk.detect.ImuChannel
import com.application.sensorforge.sdk.detect.ImuSample
import com.application.sensorforge.sdk.detect.IncidentEvidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IncidentLogTest {
    @Test fun keystoreRoundTripAndCiphertextIsOpaque() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "inc-test").apply { deleteRecursively() }
        val log = IncidentLog(dir)
        val ev = IncidentEvidence(
            "inc-test", true, 1L, 8.0, listOf(1f), "rule-based triage (no head)", null, emptyMap(), 0.01,
            null, "LOCATION_NOT_AVAILABLE", 1, "x", emptyList(), "r",
        )
        val f = log.save(ev, listOf(ImuSample(1L, ImuChannel.ACCEL, 0f, 0f, 9.8f)))
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("LOCATION_NOT_AVAILABLE"))
        val plain = log.read(f)
        assertTrue(plain.contains("\"incidentId\": \"inc-test\""))
        assertTrue(plain.contains("SIMULATION incident evidence"))
        dir.deleteRecursively()
    }
}
