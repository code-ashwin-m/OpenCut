package org.ashwin.opencut.engine.timeline

import androidx.compose.runtime.Immutable

@Immutable
data class MediaAsset(
    val id: String,
    val displayName: String,
    val durationMs: Long,
    val resolution: String,
    val fps: Int,
    val localUri: String,         // Points to local filesystem path, content:// URI, or android.resource://
    val thumbnailColor: Long,     // Used for rendering colored card fallbacks
    val fileSizeBytes: Long
) {
    val durationString: String
        get() = String.format(
            "%02d:%02d",
            (durationMs / 1000) / 60,
            (durationMs / 1000) % 60
        )
}


