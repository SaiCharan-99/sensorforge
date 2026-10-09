package com.application.sensorforge.sdk.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private val hashes = ModelCatalog.ENTRIES.associate { it.id to (it.expectedSha256 ?: ModelCatalog.builtinSha256(it)) }

    @Test fun idsUnique() = assertEquals(ModelCatalog.ENTRIES.size, ModelCatalog.ENTRIES.map { it.id }.toSet().size)

    @Test fun everyAssetHasHash() =
        assertTrue(ModelCatalog.ENTRIES.filter { it.location == ModelLocation.ASSET }.all { it.expectedSha256 != null })

    @Test fun lockIsStableAndOrderIndependent() {
        val a = ModelCatalog.lockOf(listOf("m1_tags", "c2_zxing"), hashes)
        val b = ModelCatalog.lockOf(listOf("c2_zxing", "m1_tags"), hashes)
        assertEquals(a, b); assertEquals(a.stackId(), b.stackId())
    }

    @Test fun changedHashFailsClosed() {
        val lock = ModelCatalog.lockOf(listOf("m4_hands"), hashes)
        assertTrue(ModelCatalog.check(lock, hashes).isEmpty())
        assertEquals("m4_hands", ModelCatalog.check(lock, hashes + ("m4_hands" to "00")).single().id)
        assertEquals(null, ModelCatalog.check(lock, hashes + ("m4_hands" to null)).single().actual)
    }
}
