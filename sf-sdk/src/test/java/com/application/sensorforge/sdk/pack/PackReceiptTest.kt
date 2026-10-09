package com.application.sensorforge.sdk.pack

import com.application.sensorforge.sdk.Fixtures
import com.application.sensorforge.sdk.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackReceiptTest {
    private val hashes = ModelCatalog.ENTRIES.associate { it.id to (it.expectedSha256 ?: ModelCatalog.builtinSha256(it)) }
    private val lock = ModelCatalog.lockOf(listOf("m1_tags", "c2_zxing"), hashes)

    private fun contents(spec: String = Fixtures.text("specs/sealsafe.spec.json")) = PackContents(
        Pack.manifestFor("sealsafe", 1, "verify", listOf("objects", "state", "quality"), listOf("camera", "clock")),
        spec, mapOf("state" to """{"id":"state","version":1}"""), lock = lock,
        assets = mapOf("zones.json" to "{}".toByteArray()),
    )

    @Test fun buildThenOpenRoundTripsWithSameHash() {
        val (bytes, m) = Pack.build(contents())
        val p = Pack.open(bytes, hashes)
        assertEquals(m.packSha256, p.sha256)
        assertEquals(64, p.sha256.length)
        assertEquals("sealsafe", p.manifest.packId)
        assertEquals(setOf("state"), p.contents.heads.keys)
    }

    @Test fun buildIsDeterministic() = assertEquals(Pack.build(contents()).second.packSha256, Pack.build(contents()).second.packSha256)

    @Test fun differentSpecDifferentHash() {
        val other = Fixtures.text("specs/sealsafe.spec.json").replace("\"version\": 1", "\"version\": 2")
        assertNotEquals(Pack.build(contents()).second.packSha256, Pack.build(contents(other)).second.packSha256)
    }

    @Test fun whitespaceOnlyChangeKeepsHash() {
        val spec = Fixtures.text("specs/sealsafe.spec.json")
        assertEquals(Pack.build(contents(spec)).second.packSha256, Pack.build(contents(spec.replace("  ", "   "))).second.packSha256)
    }

    @Test fun tamperedBytesRejected() {
        val bytes = Pack.build(contents()).first.copyOf()
        // Flip a byte of the stored (uncompressed-size-sensitive) content; either zip or hash check must reject.
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        assertThrows(Exception::class.java) { Pack.open(bytes, hashes) }
    }

    @Test fun notAZipRejected() { assertThrows(PackIntegrityError::class.java) { Pack.open("hello".toByteArray()) } }

    @Test fun modelHashMismatchFailsClosed() {
        val (bytes, _) = Pack.build(contents())
        val ex = assertThrows(PerceptionStackMismatch::class.java) { Pack.open(bytes, hashes + ("m1_tags" to "ff")) }
        assertEquals("m1_tags", ex.mismatches.single().id)
        assertThrows(PerceptionStackMismatch::class.java) { Pack.open(bytes, hashes + ("c2_zxing" to null)) }
    }

    private fun input(verdict: String = "FAIL") = ReceiptInput(
        "sealsafe", 1, "ab".repeat(32), "tags-qr/zxing-3.5.3/v1", mapOf("start_time" to "1760000010000"), verdict, null,
        listOf(Triple("ORDER_VIOLATED", "seal_broken>start_time", "too early")), listOf("seal_intact" to "OBSERVED"),
        null, Canonical.sha256("timeline"), "2026-10-10T10:00:00Z", 123_456L, "iQOO I2501",
    )

    @Test fun receiptVerifiesAndDetectsAnyChange() {
        val r = Receipts.create(input())
        assertTrue(Receipts.verify(r, timelineText = "timeline").valid)
        assertEquals("Receipt valid", Receipts.verify(r).label)
        assertFalse(Receipts.verify(r.replace("FAIL", "PASS")).valid)
        assertFalse(Receipts.verify(r.replace("123456", "123457")).valid)
        assertFalse(Receipts.verify(r, timelineText = "timeline2").valid)
        assertEquals("Receipt invalid", Receipts.verify(r.replace("iQOO", "Pixel")).label)
        assertFalse(Receipts.verify("{}").valid)
        assertFalse(Receipts.verify("not json").valid)
    }

    @Test fun receiptIsDeterministic() = assertEquals(Receipts.create(input()), Receipts.create(input()))
    @Test fun differentVerdictDifferentHash() =
        assertNotEquals(Receipts.field(Receipts.create(input("FAIL")), "receipt_sha256"), Receipts.field(Receipts.create(input("PASS")), "receipt_sha256"))
}
