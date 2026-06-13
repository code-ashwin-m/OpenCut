package org.ashwin.opencut.presentation.editor

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "exported_video.mp4"
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
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Text(
            text = "Brightness: ${uiState.effectSettings.brightness}",
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )

        Slider(
            value = uiState.effectSettings.brightness,
            onValueChange = onBrightnessChange,
            valueRange = -1f..1f,
            modifier = Modifier.padding(16.dp)
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        LinearProgressIndicator(
            progress = { uiState.exportProgress },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onExportVideo,
            enabled = !uiState.isExporting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(if (uiState.isExporting) "Exporting..." else "Export MP4")
        }
    }
}
