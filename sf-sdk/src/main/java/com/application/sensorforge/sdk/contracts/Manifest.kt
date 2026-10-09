package com.application.sensorforge.sdk.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `manifest.json` inside a `.sfpkg` (tech spec §5). */
@Serializable
data class PackManifest(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("pack_id") val packId: String,
    @SerialName("pack_version") val packVersion: Int,
    val mode: String,
    @SerialName("perception_profile") val perceptionProfile: List<String>,
    val sensors: List<String>,
    @SerialName("created_on_device") val createdOnDevice: Boolean,
    @SerialName("pack_sha256") val packSha256: String,
)
