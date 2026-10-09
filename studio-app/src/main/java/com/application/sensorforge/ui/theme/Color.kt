package com.application.sensorforge.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Tokens from docs/design.md §2 (light theme only).
val Background = Color(0xFFF7F8FA)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFEEF1F5)
val Outline = Color(0xFFD5DAE1)
val OutlineStrong = Color(0xFFB6BDC8)
val TextPrimary = Color(0xFF1C2430)
val TextSecondary = Color(0xFF4A5565)
val TextTertiary = Color(0xFF6B7280)
val Primary = Color(0xFF4F5BD5)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFE6E8FB)
val OnPrimaryContainer = Color(0xFF2A3290)

/** Container / content / border for one status. */
@Immutable
data class StatusColors(val container: Color, val content: Color, val border: Color)

/** Status, simulation and chart colours (not part of the M3 scheme), design.md §9. */
@Immutable
data class SensorForgeColors(
    val pass: StatusColors = StatusColors(Color(0xFFE3F4EA), Color(0xFF1E7A46), Color(0xFFA8D9BB)),
    val fail: StatusColors = StatusColors(Color(0xFFFDE8E6), Color(0xFFB42318), Color(0xFFF4B4AD)),
    val unverified: StatusColors = StatusColors(Color(0xFFFFF3D6), Color(0xFF8A5A00), Color(0xFFF2D18A)),
    val pending: StatusColors = StatusColors(Color(0xFFEEF1F5), Color(0xFF4A5565), Color(0xFFD5DAE1)),
    val info: StatusColors = StatusColors(Color(0xFFE4F0FA), Color(0xFF1F6FB2), Color(0xFFB5D3EE)),
    val simulation: StatusColors = StatusColors(Color(0xFFF1E8FB), Color(0xFF6B3FA0), Color(0xFFD2BDEB)),
    val passSolid: Color = Color(0xFF1E7A46),
    val failSolid: Color = Color(0xFFB42318),
    val unverifiedSolid: Color = Color(0xFF8A5A00),
    val chart1: Color = Color(0xFF4F5BD5),
    val chart2: Color = Color(0xFFE07A3F),
    val chart3: Color = Color(0xFF2E9C8F),
    val chartGrid: Color = Color(0xFFE5E8ED),
    val chartAxis: Color = Color(0xFF6B7280),
)

val LocalSfColors = staticCompositionLocalOf { SensorForgeColors() }
