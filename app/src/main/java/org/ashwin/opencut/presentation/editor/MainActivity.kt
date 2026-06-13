package org.ashwin.opencut.presentation.editor

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dagger.hilt.android.AndroidEntryPoint
import org.ashwin.opencut.R
import org.ashwin.opencut.presentation.preview.VideoPreviewView
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    private lateinit var previewView: VideoPreviewView
    private lateinit var inputUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = VideoPreviewView(this)
        inputUri = Uri.parse("android.resource://${packageName}/${R.raw.sample}")

        previewView.loadVideo(inputUri)

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            LaunchedEffect(uiState.effectSettings.brightness) {
                previewView.setEffect(uiState.effectSettings)
            }

            EditorScreen(
                previewView = previewView,
                uiState = uiState,
                onBrightnessChange = { viewModel.updateBrightness(it) },
                onExportVideo = { exportVideo() }
            )
        }
    }

    private fun exportVideo() {
        val outputFile = File(
            cacheDir,
            "exported_video_tmp.mp4"
        )
        viewModel.startExport(inputUri, outputFile.absolutePath)
    }
}

@Composable
fun EditorScreen(
    previewView: VideoPreviewView,
    uiState: EditorUiState,
    onBrightnessChange: (Float) -> Unit,
    onExportVideo: () -> Unit,
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

            var isPlaying by remember { mutableStateOf(true) }

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
            Text(
                text = "Tools",
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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
