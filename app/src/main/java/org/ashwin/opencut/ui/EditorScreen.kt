package org.ashwin.opencut.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.ashwin.opencut.engine.render.VideoRenderBridge
import org.ashwin.opencut.engine.timeline.Clip
import org.ashwin.opencut.engine.timeline.TimelineComposition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    initialComposition: TimelineComposition,
    onSeek: (Long) -> Unit,
    onBackToDashboard: (TimelineComposition) -> Unit,
    modifier: Modifier = Modifier

){

    var compositionState by remember { mutableStateOf(initialComposition) }
    var selectedClipId by remember { mutableStateOf<String?>(null) }

    // 1. Instantiate the native JNI Bridge
    val renderBridge = remember { VideoRenderBridge() }
    var nativeEngineHandle by remember { mutableLongStateOf(0) }

    // Initialize/release native engine handle alongside the composable lifecycle
    DisposableEffect(Unit) {
        nativeEngineHandle = renderBridge.nativeInit()
        onDispose {
            if (nativeEngineHandle != 0L) {
                renderBridge.nativeRelease(nativeEngineHandle)
                nativeEngineHandle = 0L
            }
        }
    }

    // Resolve which clip object is currently targeted inside properties view
    val selectedClip = remember(compositionState, selectedClipId) {
        compositionState.tracks
            .flatMap { it.clips }
            .firstOrNull { it.id == selectedClipId }
    }

    // Monitor composition changes and push them down to the C++ side
    LaunchedEffect(compositionState, nativeEngineHandle) {
        if (nativeEngineHandle != 0L) {
            // In production, serialize the composition state (JSON / Protocol Buffers)
            // For now, we simulate sending the update signal
            val realSerializedPayload = org.ashwin.opencut.engine.timeline.ProjectSerializer.serialize(compositionState)
            renderBridge.nativeUpdateComposition(nativeEngineHandle, realSerializedPayload)
        }
    }

    // Scaffold allows adaptivity across structural panels (Asset Picker, Preview Window, Control Deck)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editing Mode", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { onBackToDashboard(compositionState) }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back to Projects",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF141416))
            )
        },
        containerColor = Color(0xFF0F0F10)
    ) { paddingValues ->

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F10))
                .padding(paddingValues)
        ) {
            // TOP HALF: 3-Pane Editing Workbench Workspace Split
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MediaLibraryPanel(
                    onAssetSelected = { asset ->
                        // Map the imported asset to a new Video Track Clip on the timeline
                        val currentPlayhead = compositionState.currentTimeMs
                        val duration = asset.durationMs

                        val newClip = Clip(
                            id = "clip_${System.currentTimeMillis()}",
                            filePath = asset.localUri,
                            sourceInMs = 0,
                            sourceOutMs = duration,
                            timelineStartMs = currentPlayhead,
                            timelineEndMs = currentPlayhead + duration
                        )

                        // Append clip to the first available track in our timeline configuration
                        val updatedTracks = compositionState.tracks.mapIndexed { idx, track ->
                            if (idx == 0) { // Add clip directly onto Track Index 0
                                track.copy(clips = track.clips + newClip)
                            } else {
                                track
                            }
                        }

                        // Recalculate full composition runtime boundaries
                        val maxDuration =
                            updatedTracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndMs }
                                ?: 0L

                        compositionState = compositionState.copy(
                            tracks = updatedTracks,
                            durationMs = maxOf(compositionState.durationMs, maxDuration)
                        )
                    }
                )

                VerticalDivider(color = Color(0xFF2C2C2E))

                // CENTER PANE: Dynamic Preview Canvas Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (nativeEngineHandle != 0L) {
                        // We embed the standard Android TextureView inside Jetpack Compose
                        AndroidView(
                            factory = { context ->
                                TextureView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )

                                    surfaceTextureListener =
                                        object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(
                                                surfaceTexture: SurfaceTexture,
                                                width: Int,
                                                height: Int
                                            ) {
                                                // Create raw Surface and hand it over to C++ Native Engine
                                                val surface = Surface(surfaceTexture)
                                                renderBridge.nativeSetSurface(
                                                    nativeEngineHandle,
                                                    surface
                                                )

                                                // Force draw update once surface is ready
                                                renderBridge.nativeSeekTo(
                                                    nativeEngineHandle,
                                                    compositionState.currentTimeMs
                                                )
                                            }

                                            override fun onSurfaceTextureSizeChanged(
                                                surface: SurfaceTexture,
                                                width: Int,
                                                height: Int
                                            ) {
                                                // Handle aspect ratio & viewport resizing inside C++ context if needed
                                            }

                                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                                // Unbind surface from C++ engine to prevent drawing into dead surfaces
                                                renderBridge.nativeSetSurface(
                                                    nativeEngineHandle,
                                                    null
                                                )
                                                return true
                                            }

                                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                                        }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFF007AFF))
                        }
                    }
                }

                VerticalDivider(color = Color(0xFF2C2C2E))

                // RIGHT PANE: Properties Adjustments Panel
                PropertyPanel(
                    selectedClip = selectedClip,
                    onTransformChanged = { updatedTransform ->
                        selectedClipId?.let { clipId ->
                            val updatedTracks = compositionState.tracks.map { track ->
                                track.copy(clips = track.clips.map { clip ->
                                    if (clip.id == clipId) {
                                        val updated = clip.copy(transform = updatedTransform)
                                        // Trigger immediate preview render update on slider move
                                        renderBridge.nativeSeekTo(
                                            nativeEngineHandle,
                                            compositionState.currentTimeMs
                                        )
                                        updated
                                    } else {
                                        clip
                                    }
                                })
                            }
                            compositionState = compositionState.copy(tracks = updatedTracks)
                        }
                    },
                    onAdjustmentsChanged = { updatedAdjustments ->
                        selectedClipId?.let { clipId ->
                            val updatedTracks = compositionState.tracks.map { track ->
                                track.copy(clips = track.clips.map { clip ->
                                    if (clip.id == clipId) {
                                        val updated = clip.copy(adjustments = updatedAdjustments)
                                        // Trigger immediate preview render update on slider move
                                        renderBridge.nativeSeekTo(
                                            nativeEngineHandle,
                                            compositionState.currentTimeMs
                                        )
                                        updated
                                    } else clip
                                })
                            }
                            compositionState = compositionState.copy(tracks = updatedTracks)
                        }
                    }
                )
            }

            HorizontalDivider(color = Color(0xFF2C2C2E))

            // BOTTOM HALF: Multi-track Timeline Sandbox
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(Color(0xFF0C0C0D))
            ) {
                TimelineCanvas(
                    composition = compositionState,
                    selectedClipId = selectedClipId,
                    onSeek = { seekTime ->
                        renderBridge.nativeSeekTo(nativeEngineHandle, seekTime)
                        compositionState = compositionState.copy(currentTimeMs = seekTime)
                    },
                    onClipSelected = { clipId ->
                        selectedClipId = clipId
                    },
                    onClipMoved = { _, _ -> }
                )
            }
        }
    }
}
