package com.application.sensorforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// design.md §3. Inter / JetBrains Mono are not bundled yet; system sans / monospace fallback.
private fun style(size: Int, line: Int, weight: Int) =
    TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight))

val Typography = Typography(
    headlineLarge = style(28, 36, 600),
    headlineMedium = style(22, 28, 600),
    titleMedium = style(18, 24, 600),
    titleLarge = style(22, 28, 600),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 20, 400),
    labelLarge = style(16, 20, 600),
    labelMedium = style(12, 16, 600),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight(500))
val DisplayVerdict = style(40, 48, 700)
val CountdownStyle = TextStyle(fontSize = 96.sp, fontWeight = FontWeight(700), fontFeatureSettings = "tnum")
