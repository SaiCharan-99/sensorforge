package com.application.sensorforge.sdk.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Rule 6: contracts + engine stay pure Kotlin (no Android imports). */
class PurityTest {
    @Test fun contractsAndEngineHaveNoAndroidImports() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "sf-sdk/src/main/java/com/application/sensorforge/sdk") }
            .plus(generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java/com/application/sensorforge/sdk") })
            .first { it.isDirectory }
        val offenders = listOf("contracts", "engine", "detect", "train", "perception", "pack", "models", "forge")
            .flatMap { File(src, it).walk().filter { f -> f.extension == "kt" }.toList() }
            .filter { f -> f.readLines().any { it.startsWith("import android") || it.startsWith("import androidx") } }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }
}
