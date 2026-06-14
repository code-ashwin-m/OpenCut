package org.ashwin.opencut.presentation.editor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.ashwin.opencut.presentation.components.TimelineView
import org.ashwin.opencut.presentation.components.TimelineTrackState
import org.ashwin.opencut.presentation.components.TimelineTrackType
import org.ashwin.opencut.presentation.components.TimelineClipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import org.ashwin.opencut.R
import org.ashwin.opencut.presentation.home.ProjectListScreen
import org.ashwin.opencut.presentation.home.ProjectListViewModel
import org.ashwin.opencut.presentation.preview.VideoPreviewView
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    val homeViewModel: ProjectListViewModel = hiltViewModel()
                    ProjectListScreen(
                        viewModel = homeViewModel,
                        onNavigateToEditor = { projectId ->
                            navController.navigate("editor/$projectId")
                        }
                    )
                }
                composable(
                    route = "editor/{projectId}",
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val editorViewModel: EditorViewModel = hiltViewModel()
                    val uiState by editorViewModel.uiState.collectAsState()

                    val context = androidx.compose.ui.platform.LocalContext.current
                    val inputUri = remember { Uri.parse("android.resource://${context.packageName}/${R.raw.sample}") }
                    val previewView = remember {
                        VideoPreviewView(context).apply {
                            loadVideo(inputUri)
                        }
                    }

                    var isPlaying by remember { mutableStateOf(false) }
                    var currentPositionMs by remember { mutableStateOf(0L) }
                    var totalDurationMs by remember { mutableStateOf(0L) }
                    var isSeeking by remember { mutableStateOf(false) }

                    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                                    previewView.pausePlayback()
                                    isPlaying = false
                                    previewView.onPause()
                                }
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                                    previewView.onResume()
                                }
                                else -> {}
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            previewView.release()
                        }
                    }

                    LaunchedEffect(uiState.effectSettings) {
                        previewView.setEffect(uiState.effectSettings)
                    }

                    LaunchedEffect(isPlaying, isSeeking) {
                        if (!isSeeking) {
                            while (true) {
                                val duration = previewView.getDuration()
                                if (duration > 0) {
                                    totalDurationMs = duration.toLong()
                                }
                                currentPositionMs = previewView.getCurrentPosition().toLong()
                                val activePlaying = previewView.isPlaying()
                                if (activePlaying != isPlaying) {
                                    isPlaying = activePlaying
                                }
                                kotlinx.coroutines.delay(50)
                            }
                        }
                    }

                    EditorScreen(
                        previewView = previewView,
                        uiState = uiState,
                        isPlaying = isPlaying,
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = totalDurationMs,
                        onSeek = { position ->
                            currentPositionMs = position
                            previewView.seekTo(position.toInt())
                        },
                        onSeekStart = {
                            isSeeking = true
                            previewView.pausePlayback()
                            isPlaying = false
                        },
                        onSeekEnd = {
                            isSeeking = false
                            previewView.seekTo(currentPositionMs.toInt())
                        },
                        onPlayPauseToggle = {
                            previewView.togglePlayback()
                            isPlaying = previewView.isPlaying()
                        },
                        onBrightnessChange = { editorViewModel.updateBrightness(it) },
                        onContrastChange = { editorViewModel.updateContrast(it) },
                        onExposureChange = { editorViewModel.updateExposure(it) },
                        onHighlightsChange = { editorViewModel.updateHighlights(it) },
                        onShadowsChange = { editorViewModel.updateShadows(it) },
                        onExportVideo = {
                            val outputFile = File(
                                context.cacheDir,
                                "exported_video_tmp.mp4"
                            )
                            editorViewModel.startExport(inputUri, outputFile.absolutePath)
                        },
                        onBackClick = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EditorScreen(
    previewView: VideoPreviewView,
    uiState: EditorUiState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onExposureChange: (Float) -> Unit,
    onHighlightsChange: (Float) -> Unit,
    onShadowsChange: (Float) -> Unit,
    onExportVideo: () -> Unit,
    onBackClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // Upper part: Preview & Tools side-by-side
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f)
        ) {
            // Left side: Preview (75%)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.75f)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                // Transport Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPlayPauseToggle
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    val currentSec = currentPositionMs / 1000
                    val totalSec = totalDurationMs / 1000
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = String.format("%d:%02d / %d:%02d", currentSec / 60, currentSec % 60, totalSec / 60, totalSec % 60),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Right side: Tools (25%)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.25f)
                    .padding(16.dp)
                    .background(Color(0xFF1E1E1E))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Tools",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Brightness: %.2f".format(uiState.effectSettings.brightness),
                        color = Color.White
                    )

                    Slider(
                        value = uiState.effectSettings.brightness,
                        onValueChange = onBrightnessChange,
                        valueRange = -1f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.size(16.dp))

                    Text(
                        text = "Contrast: %.2f".format(uiState.effectSettings.contrast),
                        color = Color.White
                    )

                    Slider(
                        value = uiState.effectSettings.contrast,
                        onValueChange = onContrastChange,
                        valueRange = 0f..2f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.size(16.dp))

                    Text(
                        text = "Exposure: %.2f".format(uiState.effectSettings.exposure),
                        color = Color.White
                    )

                    Slider(
                        value = uiState.effectSettings.exposure,
                        onValueChange = onExposureChange,
                        valueRange = -2f..2f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.size(16.dp))

                    Text(
                        text = "Highlights: %.2f".format(uiState.effectSettings.highlights),
                        color = Color.White
                    )

                    Slider(
                        value = uiState.effectSettings.highlights,
                        onValueChange = onHighlightsChange,
                        valueRange = -1f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.size(16.dp))

                    Text(
                        text = "Shadows: %.2f".format(uiState.effectSettings.shadows),
                        color = Color.White
                    )

                    Slider(
                        value = uiState.effectSettings.shadows,
                        onValueChange = onShadowsChange,
                        valueRange = -1f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.size(16.dp))

                if (uiState.isExporting || uiState.exportProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { uiState.exportProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }

                Button(
                    onClick = onExportVideo,
                    enabled = !uiState.isExporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isExporting) "Exporting..." else "Export MP4")
                }
            }
        }

        // Lower part: Timeline
        val timelineTracks = remember(totalDurationMs) {
            listOf(
                TimelineTrackState(
                    id = "video-track-1",
                    name = "Video Track 1",
                    type = TimelineTrackType.VIDEO,
                    clips = listOf(
                        TimelineClipState(
                            id = "clip-1",
                            title = "sample.mp4",
                            startMs = 0L,
                            durationMs = totalDurationMs
                        )
                    )
                )
            )
        }

        TimelineView(
            tracks = timelineTracks,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            onSeek = onSeek,
            onSeekStart = onSeekStart,
            onSeekEnd = onSeekEnd,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.3f)
        )
    }
}
