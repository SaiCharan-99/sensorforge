package com.application.sensorforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.application.sensorforge.ui.theme.MonoStyle
import com.application.sensorforge.ui.theme.OutlineStrong
import com.application.sensorforge.ui.theme.SfTheme
import com.application.sensorforge.ui.theme.StatusColors
import com.application.sensorforge.ui.theme.TextSecondary

@Composable
fun SfCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Status = colour + icon glyph + text (design.md §1.2). */
@Composable
fun StatusChip(text: String, colors: StatusColors, icon: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(colors.container, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, color = colors.content, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(4.dp))
        Text(text.uppercase(), color = colors.content, style = MaterialTheme.typography.labelMedium)
    }
}

/** Striped SIMULATION chip; caller keeps it visible while replay data is active. */
@Composable
fun SimulationChip(detail: String? = null, modifier: Modifier = Modifier) {
    val c = SfTheme.colors.simulation
    Box(
        modifier.fillMaxWidth().heightIn(min = 32.dp).clipToBounds()
            .background(c.container, RoundedCornerShape(8.dp))
            .border(1.dp, c.border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.matchParentSize()) {
            var x = -size.height
            while (x < size.width) {
                drawLine(c.border, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 6.dp.toPx())
                x += 18.dp.toPx()
            }
        }
        Text(
            "▶ SIMULATION" + (detail?.let { ": $it" } ?: ""),
            color = c.content, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).background(c.container, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
    }
}

fun shortHash(h: String) = if (h.length <= 10) h else "${h.take(4)}…${h.takeLast(4)}"

@Composable
fun HashCard(label: String, before: String?, after: String) {
    val clip = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (before != null) {
                Text(shortHash(before), style = MonoStyle)
                Text("  →  ", style = MonoStyle)
            }
            Text(shortHash(after), style = MonoStyle)
            Spacer(Modifier.width(8.dp))
            if (before != null && before != after) StatusChip("changed", SfTheme.colors.pass, "✓")
        }
        OutlinedButton(onClick = { clip.setText(AnnotatedString(after)) }) { Text("Copy full hash") }
    }
}

/** Train (chart1) and val (chart2) loss per epoch, with legend. */
@Composable
fun LossCurveChart(train: List<Double>, value: List<Double>, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val all = train + value
            if (all.isEmpty()) return@Canvas
            val maxY = all.max().coerceAtLeast(1e-6)
            val n = maxOf(train.size, value.size).coerceAtLeast(2)
            for (i in 0..4) {
                val y = size.height * i / 4
                drawLine(c.chartGrid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            fun series(v: List<Double>, color: Color) {
                if (v.size < 2) return
                val p = Path()
                v.forEachIndexed { i, y ->
                    val px = size.width * i / (n - 1)
                    val py = size.height * (1 - (y / maxY)).toFloat()
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                drawPath(p, color, style = Stroke(2.dp.toPx()))
            }
            series(train, c.chart1)
            series(value, c.chart2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(c.chart1, "train loss")
            Legend(c.chart2, "val loss")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(12.dp, 2.dp).background(color))
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium, color = SfTheme.colors.chartAxis)
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Button(onClick, modifier.fillMaxWidth().height(56.dp), enabled = enabled, shape = RoundedCornerShape(12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    OutlinedButton(
        onClick, modifier.height(48.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

/** S16: receipt fields in monospace plus "Verify receipt" → valid / invalid, recomputed from disk. */
@Composable
fun ReceiptView(path: String?, onBack: (() -> Unit)? = null) {
    var verified by androidx.compose.runtime.remember(path) { androidx.compose.runtime.mutableStateOf<com.application.sensorforge.sdk.SensorForge.ReceiptView?>(null) }
    var checked by androidx.compose.runtime.remember(path) { androidx.compose.runtime.mutableStateOf(false) }
    val c = SfTheme.colors
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Evidence receipt", style = MaterialTheme.typography.headlineLarge)
        if (path == null || !java.io.File(path).exists()) {
            SfCard { Text("No receipt for this run.", style = MaterialTheme.typography.bodyLarge) }
        } else {
            val r = androidx.compose.runtime.remember(path) { com.application.sensorforge.sdk.SensorForge.readReceipt(path) }
            SfCard { r.fields.forEach { (k, v) -> Text("$k: $v", style = MonoStyle) } }
            PrimaryButton("Verify receipt") { verified = com.application.sensorforge.sdk.SensorForge.readReceipt(path); checked = true }
            if (checked) verified?.let {
                val ok = it.verification.valid
                StatusChip(it.verification.label + (it.verification.reason?.let { m -> ": $m" } ?: ""), if (ok) c.pass else c.fail, if (ok) "✓" else "✕")
            }
        }
        onBack?.let { SecondaryButton("Back", onClick = it) }
    }
}
