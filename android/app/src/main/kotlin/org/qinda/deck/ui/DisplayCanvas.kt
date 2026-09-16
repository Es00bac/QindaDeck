package org.qinda.deck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.qinda.deck.data.DisplayCfg
import org.qinda.deck.data.DisplayConfig

/** A point to draw on the canvas, in display-local pixels. */
data class CanvasMark(
    val displayId: String,
    val x: Int,
    val y: Int,
    val label: String,
    val highlight: Boolean = false,
)

/**
 * The virtual desktop, drawn to scale, with macro coordinates on it.
 *
 * This is how a macro's coordinates become editable by a human: typing "1400,
 * 700" into a box tells you nothing about where that is, whereas tapping the spot
 * on a scale drawing of your actual monitors does. Taps report display-local
 * pixels for whichever display was hit, which is exactly what a step stores.
 */
@Composable
fun DisplayCanvas(
    config: DisplayConfig,
    marks: List<CanvasMark> = emptyList(),
    path: List<CanvasMark> = emptyList(),
    onTap: ((displayId: String, x: Int, y: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val vw = config.virtualWidth.coerceAtLeast(1)
    val vh = config.virtualHeight.coerceAtLeast(1)
    val minX = config.displays.minOfOrNull { it.x } ?: 0
    val minY = config.displays.minOfOrNull { it.y } ?: 0

    Box(modifier.fillMaxWidth().height(200.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .then(
                    if (onTap == null) Modifier else Modifier.pointerInput(config) {
                        detectTapGestures { tap ->
                            val scale = minOf(size.width / vw.toFloat(), size.height / vh.toFloat())
                            val offX = (size.width - vw * scale) / 2f
                            val offY = (size.height - vh * scale) / 2f
                            val virtX = ((tap.x - offX) / scale).toInt() + minX
                            val virtY = ((tap.y - offY) / scale).toInt() + minY
                            val hit = config.displays.firstOrNull {
                                virtX >= it.x && virtY >= it.y &&
                                    virtX < it.x + it.w && virtY < it.y + it.h
                            } ?: return@detectTapGestures
                            onTap(hit.id, virtX - hit.x, virtY - hit.y)
                        }
                    }
                )
        ) {
            val scale = minOf(size.width / vw.toFloat(), size.height / vh.toFloat())
            val offX = (size.width - vw * scale) / 2f
            val offY = (size.height - vh * scale) / 2f

            fun toCanvas(d: DisplayCfg, lx: Int, ly: Int) = Offset(
                offX + (d.x - minX + lx) * scale,
                offY + (d.y - minY + ly) * scale,
            )

            config.displays.forEach { d ->
                val topLeft = Offset(offX + (d.x - minX) * scale, offY + (d.y - minY) * scale)
                val sz = Size(d.w * scale, d.h * scale)
                drawRect(color = surface, topLeft = topLeft, size = sz)
                drawRect(
                    color = if (d.primary) primary.copy(alpha = 0.7f) else outline,
                    topLeft = topLeft,
                    size = sz,
                    style = Stroke(width = if (d.primary) 2.5f else 1.5f),
                )
                val label = "${d.name.ifBlank { d.id }}  ${d.w}×${d.h}"
                val laid = measurer.measure(
                    label,
                    TextStyle(fontSize = 9.sp, color = onSurfaceVariant),
                )
                drawText(laid, topLeft = topLeft + Offset(6f, 6f))
            }

            // The travel path, so a multi-step move reads as a route.
            if (path.size > 1) {
                var prev: Offset? = null
                path.forEach { m ->
                    val d = config.resolve(m.displayId) ?: return@forEach
                    val p = toCanvas(d, m.x, m.y)
                    prev?.let {
                        drawLine(
                            color = secondary.copy(alpha = 0.55f),
                            start = it,
                            end = p,
                            strokeWidth = 2f,
                        )
                    }
                    prev = p
                }
            }

            marks.forEach { m ->
                val d = config.resolve(m.displayId) ?: return@forEach
                val p = toCanvas(d, m.x, m.y)
                val c = if (m.highlight) secondary else primary
                drawCircle(color = c.copy(alpha = 0.25f), radius = 11f, center = p)
                drawCircle(color = c, radius = 5f, center = p)
                if (m.label.isNotEmpty()) {
                    val laid = measurer.measure(
                        m.label,
                        TextStyle(fontSize = 9.sp, color = c),
                    )
                    drawText(laid, topLeft = p + Offset(8f, -4f))
                }
            }
        }
    }
}
