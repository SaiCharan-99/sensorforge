package com.application.sensorforge.sdk.detect

import com.application.sensorforge.sdk.contracts.Contracts
import com.application.sensorforge.sdk.contracts.DetectSpec
import com.application.sensorforge.sdk.detect.IncidentState.ARMED
import com.application.sensorforge.sdk.detect.IncidentState.CANCELLED
import com.application.sensorforge.sdk.detect.IncidentState.COUNTDOWN
import com.application.sensorforge.sdk.detect.IncidentState.EVIDENCE_SAVED
import com.application.sensorforge.sdk.detect.IncidentState.RESOLVED
import com.application.sensorforge.sdk.detect.IncidentState.SOS_SIMULATED
import com.application.sensorforge.sdk.train.Head
import com.application.sensorforge.sdk.train.HeadTrainer
import com.application.sensorforge.sdk.train.TrainConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class IncidentControllerTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "fixtures/imu").isDirectory }
    private val spec: DetectSpec = Contracts.parseDetect(File(root(), "fixtures/specs/crashguard.detect.json").readText())
    private val replays = File(root(), "fixtures/imu").listFiles { f -> f.name.startsWith("impact_") }!!.sorted()
        .map { ImuReplay.parseCsv(it.name, it.readText()) }

    /** Synthetic vigorous hand shake: ±4 g at 6 Hz, gyro ~500 dps, never still. */
    private fun shake(durS: Double, t0: Long = 0L): List<ImuSample> = (0..(durS * 100).toInt()).flatMap { i ->
        val t = t0 + i * 10_000_000L
        val ph = 2 * PI * 6 * i / 100.0
        listOf(
            ImuSample(t, ImuChannel.ACCEL, (4 * WindowFeatures.G * sin(ph)).toFloat(), 0f, WindowFeatures.G.toFloat()),
            ImuSample(t, ImuChannel.GYRO, (9 * sin(ph)).toFloat(), 0f, 0f),
            ImuSample(t, ImuChannel.ROTATION, 0f, 0f, 0f, 1f),
        )
    }

    private fun run(c: IncidentController, samples: List<ImuSample>, sim: Boolean) =
        samples.sortedWith(compareBy({ it.tNanos }, { it.channel })).forEach { c.onSample(it, sim) }

    private fun trainedHead(): Head {
        val impact = replays.flatMapIndexed { i, r -> ImuDataset.examples(r.samples, "impact_like", r.name, i >= 9, 3.0, 0.5, 100, 2.0) }
        val sh = (0 until 6).flatMap { s -> ImuDataset.examples(shake(6.0, s * 100_000_000_000L), "shake", "shake$s", s == 5, 3.0, 0.5, 100, 2.0) }
        val (h, _) = HeadTrainer.train(
            "imu_risk", listOf("shake", "impact_like"), impact + sh, TrainConfig(),
            datasetVersion = "t", trainedAt = "t", deviceFingerprint = "jvm",
        )
        return h
    }

    @Test fun tC01_shakeNeverRaisesIncident() {
        for (head in listOf(null, trainedHead())) {
            val c = IncidentController(spec, head)
            c.arm(0)
            run(c, shake(10.0), sim = false)
            assertTrue(c.state.value in setOf(ARMED, IncidentState.CANDIDATE))
            assertFalse(c.trace.value.any { it.state == IncidentState.SUSPECTED || it.state == COUNTDOWN })
            assertTrue(c.trace.value.any { it.message.startsWith("no incident") })
        }
    }

    @Test fun tC02_impactReplayReachesCountdown() {
        val head = trainedHead()
        for (r in replays.takeLast(3)) for (h in listOf(head, null)) { // held-out replays
            val c = IncidentController(spec, h)
            c.arm(0)
            run(c, r.samples, sim = r.simulation)
            assertEquals("${r.name} head=${h != null}: ${c.trace.value.last().message}", COUNTDOWN, c.state.value)
            assertTrue(c.simulation)
        }
    }

    private fun toCountdown(): Pair<IncidentController, Long> {
        val c = IncidentController(spec, null)
        c.arm(0)
        run(c, replays.last().samples, sim = true)
        assertEquals(COUNTDOWN, c.state.value)
        return c to c.trace.value.last().tNanos
    }

    @Test fun tC03_timeoutSavesEvidenceThenManualSimulatedSos() {
        val (c, t) = toCountdown()
        c.tick(t + c.countdownNanos - 1)
        assertEquals(COUNTDOWN, c.state.value)
        c.tick(t + c.countdownNanos)
        assertEquals(EVIDENCE_SAVED, c.state.value) // auto_escalate = false
        val ev = c.evidence.value!!
        assertEquals("LOCATION_NOT_AVAILABLE", ev.locationStatus)
        assertTrue(ev.simulation)
        assertEquals(IncidentController.receipt(ev), ev.receiptSha256)
        c.sendSimulatedSos(t + c.countdownNanos + 1)
        assertEquals(SOS_SIMULATED, c.state.value)
        assertFalse(c.trace.value.any { "SENT" in it.message })
        assertTrue(c.trace.value.last().message.contains("SIMULATED"))
        c.resolve(t + c.countdownNanos + 2)
        assertEquals(RESOLVED, c.state.value)
    }

    @Test fun autoEscalateGoesStraightToSimulatedSos() {
        val c = IncidentController(spec.copy(incident = spec.incident.copy(autoEscalate = true)), null)
        c.arm(0)
        run(c, replays.last().samples, sim = true)
        c.tick(Long.MAX_VALUE)
        assertEquals(SOS_SIMULATED, c.state.value)
    }

    @Test fun tC04_cancelIsAuditedAndSendsNothing() {
        var sends = 0
        val (c0, t) = toCountdown()
        c0.cancel(t + 1)
        assertEquals(CANCELLED, c0.state.value)
        assertNull(c0.evidence.value)
        assertTrue(c0.trace.value.last().message.contains("no alert"))
        c0.tick(Long.MAX_VALUE)
        assertEquals(CANCELLED, c0.state.value)
        val counting = object : IncidentTransport {
            override fun send(evidence: IncidentEvidence): TransportResult { sends++; return TransportResult.SIMULATED }
        }
        val c = IncidentController(spec, null, counting)
        c.arm(0); run(c, replays.last().samples, true); c.cancel(Long.MAX_VALUE - 1); c.tick(Long.MAX_VALUE)
        assertEquals(0, sends)
    }

    @Test fun droppedSamplesAreNotObservableNotIncident() {
        val r = replays.last()
        val peak = ImuDataset.peakNanos(r.samples)!!
        // gyro drops out right after the impact
        val broken = r.samples.filter { it.channel != ImuChannel.GYRO || it.tNanos < peak }
        val c = IncidentController(spec, null)
        c.arm(0)
        run(c, broken, sim = true)
        assertEquals(ARMED, c.state.value)
        assertTrue(c.trace.value.any { it.message.startsWith("NOT_OBSERVABLE") })
    }

    @Test fun disarmIgnoredDuringOpenIncident() {
        val (c, t) = toCountdown()
        c.disarm(t + 1)
        assertEquals(COUNTDOWN, c.state.value)
    }

    @Test fun thresholdModeUsesCalibratedPeakAndIgnoresHead() {
        val r = replays.last()
        val peak = ImuDataset.peakNanos(r.samples)!!
        val peakG = r.samples.first { it.tNanos == peak && it.channel == ImuChannel.ACCEL }
            .let { kotlin.math.sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble()) / WindowFeatures.G }
        // a head that always says "shake" would reject; threshold mode must not consult it
        val above = IncidentController(spec, trainedHead()).apply {
            setSettings(DetectionSettings(DetectionSettings.Mode.THRESHOLD, peakG - 0.5)); arm(0)
        }
        run(above, r.samples, true)
        assertEquals(COUNTDOWN, above.state.value)
        assertTrue(above.trace.value.any { "threshold" in it.message && "calibrated" in it.message })

        val below = IncidentController(spec, null).apply {
            setSettings(DetectionSettings(DetectionSettings.Mode.THRESHOLD, peakG + 0.5)); arm(0)
        }
        run(below, r.samples, true)
        assertTrue(below.state.value in setOf(ARMED, IncidentState.CANDIDATE))
        assertTrue(below.trace.value.any { it.message.endsWith("[threshold rejected]") })
    }
}
