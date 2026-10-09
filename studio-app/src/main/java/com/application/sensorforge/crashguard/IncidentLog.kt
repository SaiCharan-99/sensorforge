package com.application.sensorforge.crashguard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.application.sensorforge.sdk.detect.ImuReplay
import com.application.sensorforge.sdk.detect.ImuSample
import com.application.sensorforge.sdk.detect.IncidentEvidence
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Incident evidence at rest, encrypted with an Android Keystore AES-256-GCM key (CLAUDE.md stack).
 * File layout: `<id>.enc` = 12-byte IV ‖ ciphertext of {evidence JSON + frozen IMU CSV}.
 */
class IncidentLog(private val dir: File) {
    private val json = Json { prettyPrint = true }

    fun save(ev: IncidentEvidence, frozen: List<ImuSample>): File {
        dir.mkdirs()
        val header = listOf(if (ev.simulation) "SIMULATION incident evidence" else "LIVE incident evidence", "incident=${ev.incidentId}")
        val plain = (json.encodeToString(IncidentEvidence.serializer(), ev) + "\n---IMU---\n" + ImuReplay.toCsv(header, frozen)).toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val f = File(dir, "${ev.incidentId}.enc")
        f.writeBytes(c.iv + c.doFinal(plain))
        return f
    }

    fun read(f: File): String {
        val b = f.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }

    fun list(): List<File> = dir.listFiles { f -> f.extension == "enc" }.orEmpty().sortedBy { it.name }

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private companion object { const val ALIAS = "sf_incident_log" }
}
