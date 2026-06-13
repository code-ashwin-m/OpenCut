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

                    DisposableEffect(Unit) {
                        onDispose {
                            previewView.release()
                        }
                    }

                    LaunchedEffect(uiState.effectSettings) {
                        previewView.setEffect(uiState.effectSettings)
                    }

                    EditorScreen(
                        previewView = previewView,
                        uiState = uiState,
                        onBrightnessChange = { editorViewModel.updateBrightness(it) },
                        onContrastChange = { editorViewModel.updateContrast(it) },
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
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onExportVideo: () -> Unit,
    onBackClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize()
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

            var isPlaying by remember { mutableStateOf(false) }

            // Transport Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        previewView.togglePlayback()
                        isPlaying = previewView.isPlaying()
                    }
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Right side: Tools (25%)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.25f)
                .padding(16.dp)
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
                    color = Color.White
                )
            }

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

            Spacer(modifier = Modifier.weight(1f))

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
}
