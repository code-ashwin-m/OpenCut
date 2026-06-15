package org.ashwin.opencut.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import org.ashwin.opencut.engine.timeline.Clip
import org.ashwin.opencut.engine.timeline.TimelineComposition
import kotlin.math.max

@OptIn(ExperimentalTextApi::class)
@Composable
fun TimelineCanvas(
    composition: TimelineComposition,
    selectedClipId: String?,
    onSeek: (Long) -> Unit,
    onClipSelected: (String?) -> Unit,
    onClipMoved: (clipId: String, newStartMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // Zoom factor: Milliseconds represented per pixel
    var msPerPx by remember { mutableStateOf(20f) }
    var scrollOffsetPx by remember { mutableStateOf(0f) }

    val trackHeight = 60.dp
    val trackSpacing = 12.dp
    val timelineRulerHeight = 30.dp

    // Multi-touch gestures tracking zoom scale
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        msPerPx = (msPerPx / zoomChange).coerceIn(1f, 100f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .transformable(state = transformableState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        // Convert tap position to track indexes and time
                        val trackHeightPx = trackHeight.toPx()
                        val spacingPx = trackSpacing.toPx()
                        val rulerHeightPx = timelineRulerHeight.toPx()

                        val relativeY = offset.y - rulerHeightPx
                        val targetTrackIndex = (relativeY / (trackHeightPx + spacingPx)).toInt()

                        val tappedTimeMs = ((offset.x + scrollOffsetPx) * msPerPx).toLong()

                        // Verify if tap bounds hit an existing clip
                        var hitClipId: String? = null
                        if (targetTrackIndex >= 0 && targetTrackIndex < composition.tracks.size) {
                            val track = composition.tracks[targetTrackIndex]
                            val clickedClip = track.clips.firstOrNull { clip ->
                                tappedTimeMs in clip.timelineStartMs..clip.timelineEndMs
                            }
                            hitClipId = clickedClip?.id
                        }

                        onClipSelected(hitClipId)
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scrollOffsetPx = max(0f, scrollOffsetPx - dragAmount.x)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeightPx = trackHeight.toPx()
            val trackSpacingPx = trackSpacing.toPx()
            val timelineRulerHeightPx = timelineRulerHeight.toPx()

            // 1. Draw Timeline Ruler
            drawRuler(msPerPx, scrollOffsetPx, size.width)

            // 2. Draw Tracks & Clips
            composition.tracks.forEachIndexed { index, track ->
                val trackTopY = timelineRulerHeightPx + index * (trackHeightPx + trackSpacingPx)

                // Draw Track background
                drawRect(
                    color = Color(0xFF1E1E1E),
                    topLeft = Offset(0f, trackTopY),
                    size = Size(size.width, trackHeightPx)
                )

                // Draw Track clips
                track.clips.forEach { clip ->
                    val clipLeftPx = (clip.timelineStartMs / msPerPx) - scrollOffsetPx
                    val clipWidthPx = clip.durationOnTimelineMs / msPerPx

                    val isSelected = clip.id == selectedClipId

                    if (clipLeftPx + clipWidthPx > 0 && clipLeftPx < size.width) {
                        drawClip(
                            clip = clip,
                            left = clipLeftPx,
                            top = trackTopY,
                            width = clipWidthPx,
                            height = trackHeightPx,
                            isSelected = isSelected
                        )
                    }
                }
            }

            // 3. Draw Current Playhead Line
            val playheadX = (composition.currentTimeMs / msPerPx) - scrollOffsetPx
            if (playheadX in 0f..size.width) {
                drawLine(
                    color = Color.Red,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, size.height),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }
    }
}

private fun DrawScope.drawRuler(msPerPx: Float, scrollOffsetPx: Float, width: Float) {
    val intervalMs = 1000 // Tick mark every 1 second
    val startMs = (scrollOffsetPx * msPerPx).toLong()
    val endMs = startMs + (width * msPerPx).toLong()

    val roundedStartMs = (startMs / intervalMs) * intervalMs

    for (t in roundedStartMs..endMs step intervalMs.toLong()) {
        val x = (t / msPerPx) - scrollOffsetPx
        if (x in 0f..width) {
            drawLine(
                color = Color.DarkGray,
                start = Offset(x, 0f),
                end = Offset(x, 15.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

private fun DrawScope.drawClip(
    clip: Clip,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    isSelected: Boolean
) {
    // Draw clip background card
    drawRect(
        color = Color(0xFF3A3A3C),
        topLeft = Offset(left, top),
        size = Size(width, height)
    )

    if (isSelected) {
        // Draw an active, highlight neon-blue thick border around the entire bounds of the clip
        drawRect(
            color = Color(0xFF007AFF),
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 3.dp.toPx())
        )
    } else {
        // Subtle standard border accents
        drawRect(
            color = Color(0xFF5A5A5C),
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 1.dp.toPx())
        )
    }

}
