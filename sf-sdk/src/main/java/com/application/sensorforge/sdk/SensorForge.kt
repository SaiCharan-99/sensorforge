package com.application.sensorforge.sdk

/**
 * Public SDK entry point (tech spec §4). `load` and `forge` are added as the
 * runtime and forge slices land; end apps must use only this API.
 */
object SensorForge {
    const val SCHEMA_VERSION = "3.0"
}
