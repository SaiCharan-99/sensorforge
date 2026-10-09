package com.application.sensorforge.sdk

import java.io.File

/** Loads files from the repo-level `fixtures/` directory. */
object Fixtures {
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }
        .first { it.isDirectory }

    fun text(path: String): String = File(root, path).readText()
}
