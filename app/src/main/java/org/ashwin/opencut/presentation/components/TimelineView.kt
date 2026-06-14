package org.ashwin.opencut.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.max

enum class TimelineTrackType {
    VIDEO,
    AUDIO,
    EFFECT
}

data class TimelineClipState(
    val id: String,
    val title: String,
    val startMs: Long,
    val durationMs: Long,
    val colorStart: Color = Color(0xFF8E2DE2), // Standard Indigo/Purple gradient colors
    val colorEnd: Color = Color(0xFF4A00E0)
)

data class TimelineTrackState(
    val id: String,
    val name: String,
    val type: TimelineTrackType,
    val clips: List<TimelineClipState>
)

@Composable
fun TimelineView(
    tracks: List<TimelineTrackState>,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onSeek: (positionMs: Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
    pixelsPerSecond: Float = 100f // Unused in fixed-track model, but kept for signature compatibility
) {
    val rulerHeight = 35.dp
    val trackHeight = 60.dp
    val trackGap = 8.dp

    val textPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.DKGRAY
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
    }

    val clipTextPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.NORMAL)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFF141416)) // Sleek dark aesthetic
    ) {
        val width = constraints.maxWidth.toFloat()

        val density = LocalDensity.current

        val paddingPx = with(density) {
            16.dp.toPx()
        }

        val trackWidth = width - (2f * paddingPx)

        // Track the current position using rememberUpdatedState to avoid cancelling the pointerInput coroutine
        val currentPositionState = rememberUpdatedState(currentPositionMs)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(totalDurationMs) {
                    if (totalDurationMs <= 0L) return@pointerInput

                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            try {
                                onSeekStart()

                                val activeTrackWidth = size.width - 2f * paddingPx
                                if (activeTrackWidth > 0) {
                                    val fraction = ((down.position.x - paddingPx) / activeTrackWidth).coerceIn(0f, 1f)
                                    val targetTimeMs = (fraction * totalDurationMs.toFloat()).toLong()
                                    onSeek(targetTimeMs)

                                    drag(down.id) { change ->
                                        val dragFraction = ((change.position.x - paddingPx) / activeTrackWidth).coerceIn(0f, 1f)
                                        val dragTimeMs = (dragFraction * totalDurationMs.toFloat()).toLong()
                                        onSeek(dragTimeMs)
                                        change.consume()
                                    }
                                }
                            } finally {
                                onSeekEnd()
                            }
                        }
                    }
                }
        ) {
            if (trackWidth <= 0) return@Canvas

            // Draw Ruler Line & Ticks
            val rulerY = rulerHeight.toPx()
            drawLine(
                color = Color(0xFF2E2E32),
                start = Offset(paddingPx, rulerY),
                end = Offset(width - paddingPx, rulerY),
                strokeWidth = 1.dp.toPx()
            )

            // Calculate ruler ticks dynamically based on duration
            val totalSeconds = (totalDurationMs.toFloat() / 1000f).toInt()
            val tickInterval = when {
                totalSeconds <= 15 -> 1
                totalSeconds <= 60 -> 5
                totalSeconds <= 300 -> 30
                else -> 60
            }

            textPaint.textSize = 10.dp.toPx()

            for (s in 0..max(totalSeconds, 1) step tickInterval) {
                val fraction = if (totalSeconds > 0) s.toFloat() / totalSeconds.toFloat() else 0f
                val tickX = paddingPx + fraction * trackWidth

                val isMajorTick = s % (tickInterval * 2) == 0 || s == 0 || s == totalSeconds
                val tickLength = if (isMajorTick) 12.dp.toPx() else 6.dp.toPx()

                drawLine(
                    color = if (isMajorTick) Color(0xFF88888C) else Color(0xFF444448),
                    start = Offset(tickX, rulerY - tickLength),
                    end = Offset(tickX, rulerY),
                    strokeWidth = (if (isMajorTick) 1.5f else 1f).dp.toPx()
                )

                if (isMajorTick) {
                    val minutes = s / 60
                    val seconds = s % 60
                    val timeStr = String.format("%d:%02d", minutes, seconds)
                    drawContext.canvas.nativeCanvas.drawText(
                        timeStr,
                        tickX,
                        rulerY - 16.dp.toPx(),
                        textPaint
                    )
                }
            }

            // Draw Tracks & Clips
            val trackGapPx = trackGap.toPx()
            val trackHeightPx = trackHeight.toPx()

            tracks.forEachIndexed { index, track ->
                val trackY = rulerY + trackGapPx + index.toFloat() * (trackHeightPx + trackGapPx)

                // Draw Track Lane Background representing the video duration (spanning full trackWidth)
                drawRoundRect(
                    color = Color(0xFF1E1E22),
                    topLeft = Offset(paddingPx, trackY),
                    size = Size(trackWidth, trackHeightPx),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )

                // Draw Clips inside the track
                track.clips.forEach { clip ->
                    val clipStartFraction = if (totalDurationMs > 0L) clip.startMs.toFloat() / totalDurationMs.toFloat() else 0f
                    val clipDurationFraction = if (totalDurationMs > 0L) clip.durationMs.toFloat() / totalDurationMs.toFloat() else 1f

                    val clipStartX = paddingPx + clipStartFraction * trackWidth
                    val clipWidth = clipDurationFraction * trackWidth

                    if (clipWidth > 0) {
                        // Draw Clip background gradient
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(clip.colorStart, clip.colorEnd)
                            ),
                            topLeft = Offset(clipStartX, trackY),
                            size = Size(clipWidth, trackHeightPx),
                            cornerRadius = CornerRadius(6.dp.toPx())
                        )

                        // Draw Clip thin border highlight
                        drawRoundRect(
                            color = Color(0x60FFFFFF),
                            topLeft = Offset(clipStartX, trackY),
                            size = Size(clipWidth, trackHeightPx),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx())
                        )

                        // Draw Clip title text (clipped)
                        clipTextPaint.textSize = 11.dp.toPx()
                        val textPaddingPx = 10.dp.toPx()

                        if (clipWidth > textPaddingPx * 2) {
                            val textY = trackY + (trackHeightPx / 2f) - ((clipTextPaint.descent() + clipTextPaint.ascent()) / 2f)

                            drawContext.canvas.save()
                            val clipPath = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = clipStartX,
                                        top = trackY,
                                        right = clipStartX + clipWidth,
                                        bottom = trackY + trackHeightPx,
                                        cornerRadius = CornerRadius(6.dp.toPx())
                                    )
                                )
                            }
                            drawContext.canvas.clipPath(clipPath)

                            drawContext.canvas.nativeCanvas.drawText(
                                clip.title,
                                clipStartX + textPaddingPx,
                                textY,
                                clipTextPaint
                            )

                            drawContext.canvas.restore()
                        }
                    }
                }
            }

            // Draw Playhead Line and Handle
            val playheadColor = Color(0xFF00E5FF) // Cyber neon cyan playhead
            val playheadFraction = if (totalDurationMs > 0L) currentPositionMs.toFloat() / totalDurationMs.toFloat() else 0f
            val playheadX = paddingPx + playheadFraction * trackWidth

            // Playhead Line
            drawLine(
                color = playheadColor,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 2.dp.toPx()
            )

            // Playhead Handle (inverted pentagon shield at the top)
            val handleWidth = 14.dp.toPx()
            val handleHeight = 22.dp.toPx()
            val handlePath = Path().apply {
                moveTo(playheadX - handleWidth / 2f, 0f)
                lineTo(playheadX + handleWidth / 2f, 0f)
                lineTo(playheadX + handleWidth / 2f, handleHeight * 0.7f)
                lineTo(playheadX, handleHeight)
                lineTo(playheadX - handleWidth / 2f, handleHeight * 0.7f)
                close()
            }
            drawPath(handlePath, color = playheadColor)

            // Playhead glowing center dot
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = Offset(playheadX, handleHeight * 0.4f)
            )
        }
    }
}
