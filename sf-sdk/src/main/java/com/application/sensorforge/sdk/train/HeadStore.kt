package com.application.sensorforge.sdk.train

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Immutable head versions on disk (tech spec §16.4): `<root>/<headId>/v<n>/{head.json,training.json}`
 * plus `active.txt`. New versions are never active until [promote]; [promote] of an older
 * version is the rollback.
 */
class HeadStore(root: File, val headId: String) {
    private val dir = File(root, headId)
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun dirOf(version: Int) = File(dir, "v$version")

    fun versions(): List<Int> =
        dir.listFiles { f -> f.isDirectory && f.name.startsWith("v") }.orEmpty()
            .mapNotNull { it.name.drop(1).toIntOrNull() }.sorted()

    fun nextVersion(): Int = (versions().maxOrNull() ?: 0) + 1

    fun save(head: Head, report: TrainingReport): File {
        require(head.id == headId && report.headVersion == head.version)
        val v = File(dir, "v${head.version}")
        check(!v.exists()) { "head version ${head.version} already exists (versions are immutable)" }
        v.mkdirs()
        File(v, "head.json").writeText(json.encodeToString(Head.serializer(), head))
        File(v, "training.json").writeText(json.encodeToString(TrainingReport.serializer(), report))
        return v
    }

    fun load(version: Int): Head = json.decodeFromString(Head.serializer(), File(dir, "v$version/head.json").readText())

    fun report(version: Int): TrainingReport =
        json.decodeFromString(TrainingReport.serializer(), File(dir, "v$version/training.json").readText())

    fun active(): Int? = File(dir, "active.txt").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        ?.takeIf { it in versions() }

    fun promote(version: Int) {
        require(version in versions()) { "no head version $version" }
        File(dir, "active.txt").writeText("$version")
    }
}
