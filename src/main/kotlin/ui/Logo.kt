package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App mark: a plugged-in phone showing a shell prompt. Drawn on a 36-unit grid
 * so it stays crisp at any size.
 */
@Composable
fun AppLogo(size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).semantics { contentDescription = "adbGUI" }) {
        val u = this.size.minDimension / 36f
        val line = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

        drawRoundRect(Bench.Raised, cornerRadius = CornerRadius(9f * u))

        // Phone body
        val phoneW = 16f * u
        val phoneH = 23f * u
        val left = (this.size.width - phoneW) / 2f
        val top = 4.5f * u
        drawRoundRect(
            Bench.Chalk,
            topLeft = Offset(left, top),
            size = Size(phoneW, phoneH),
            cornerRadius = CornerRadius(3.5f * u),
            style = line,
        )

        // Cable leaving through the bottom edge
        val centerX = this.size.width / 2f
        drawLine(
            Bench.Chalk,
            Offset(centerX, top + phoneH),
            Offset(centerX, this.size.height),
            strokeWidth = line.width,
        )

        // Shell prompt on the screen
        val px = left + 4.5f * u
        val py = top + phoneH / 2f
        val chevron = Path().apply {
            moveTo(px, py - 3f * u)
            lineTo(px + 3.5f * u, py)
            lineTo(px, py + 3f * u)
        }
        drawPath(chevron, Bench.Signal, style = line)
        drawLine(
            Bench.Signal,
            Offset(px + 5.5f * u, py + 3f * u),
            Offset(px + 8.5f * u, py + 3f * u),
            strokeWidth = line.width,
            cap = StrokeCap.Round,
        )
    }
}
