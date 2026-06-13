package org.ashwin.opencut

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.ashwin.opencut.core.EffectSettings
import org.ashwin.opencut.export.ExportCallback
import org.ashwin.opencut.export.VideoExporter
import org.ashwin.opencut.ui.EditorViewModel
import org.ashwin.opencut.ui.VideoPreviewView
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<EditorViewModel>()

    private lateinit var previewView: VideoPreviewView
    private lateinit var exporter: VideoExporter

    private lateinit var inputUri: Uri

    private lateinit var effectSettings: EffectSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        effectSettings = EffectSettings()

        previewView = VideoPreviewView(this)
        exporter = VideoExporter(this)

        inputUri = Uri.parse("android.resource://${packageName}/${R.raw.sample}")

        previewView.loadVideo(inputUri)
        previewView.setEffect(effectSettings)

        setContent {
            EditorScreen(
                previewView = previewView,
                viewModel = viewModel,
                effectSettings = effectSettings,
                onExportVideo = {
                    exportVideo()
                }
            )
        }
    }

    private fun exportVideo() {

        val outputFile = File(
            getExternalFilesDir(
                Environment.DIRECTORY_MOVIES
            ),
            "exported_video.mp4"
        )

        viewModel.startExport()

        exporter.export(
            inputUri,
            outputFile.absolutePath,
            effectSettings,
            object : ExportCallback {
                override fun onProgress(progress: Float) {
                    runOnUiThread {
                        Log.i("EXPORT", "Completed $progress")
                        viewModel.updateProgress(
                            progress
                        )
                    }
                }

                override fun onSuccess(outputPath: String?) {
                    runOnUiThread {
                        viewModel.finishExport()
                    }
                }

                override fun onError(exception: Exception?) {
                    runOnUiThread {
                        viewModel.failExport()
                    }
                }
            })
    }
}

@Composable
fun EditorScreen(
    previewView: VideoPreviewView,
    viewModel: EditorViewModel,
    effectSettings: EffectSettings,
    onExportVideo: () -> Unit,
) {
    var brightnessState by remember { mutableFloatStateOf(effectSettings.brightness) }

    Column (
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Text(
            text = "Brightness: $brightnessState",
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )

        Slider(
            value = effectSettings.brightness ,
            onValueChange = { value ->
                brightnessState = value
                effectSettings.brightness = value
            },
            valueRange = -1f..1f,
            modifier = Modifier.padding(16.dp)
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        LinearProgressIndicator(
            progress = { viewModel.exportProgress },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onExportVideo,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ){
            Text("Export MP4")
        }
    }
}




