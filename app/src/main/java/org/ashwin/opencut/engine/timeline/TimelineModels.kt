package org.ashwin.opencut.engine.timeline

import androidx.compose.runtime.Immutable

enum class TrackType {
    VIDEO, AUDIO, TEXT, OVERLAY, ADJUSTMENT
}

@Immutable
data class VideoTransform(
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f,
    val rotation: Float = 0.0f,
    val translationX: Float = 0.0f,
    val translationY: Float = 0.0f
)

@Immutable
data class ColorAdjustments(
    val brightness: Float = 0.0f,  // Range: -1.0 to 1.0
    val contrast: Float = 1.0f,    // Range: 0.0 to 2.0
    val saturation: Float = 1.0f,  // Range: 0.0 to 2.0
    val exposure: Float = 0.0f     // Range: -2.0 to 2.0
)

@Immutable
data class Clip(
    val id: String,
    val filePath: String,
    val sourceInMs: Long,          // Trim start point inside raw media asset
    val sourceOutMs: Long,         // Trim end point inside raw media asset
    val timelineStartMs: Long,     // Start timestamp on main timeline
    val timelineEndMs: Long,       // End timestamp on main timeline
    val speed: Float = 1.0f,
    val transform: VideoTransform = VideoTransform(),
    val adjustments: ColorAdjustments = ColorAdjustments()
) {
    val durationOnTimelineMs: Long
        get() = timelineEndMs - timelineStartMs
}

@Immutable
data class Track(
    val id: String,
    val type: TrackType,
    val clips: List<Clip> = emptyList(),
    val isMuted: Boolean = false,
    val isLocked: Boolean = false
)

@Immutable
data class TimelineComposition(
    val tracks: List<Track> = emptyList(),
    val durationMs: Long = 0,
    val currentTimeMs: Long = 0,
    val canvasAspectRatio: Float = 16f / 9f
)