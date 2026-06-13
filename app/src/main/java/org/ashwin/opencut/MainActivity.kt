package org.ashwin.opencut

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import org.ashwin.opencut.ui.VideoPreviewView

class MainActivity : ComponentActivity() {

    private lateinit var previewView: VideoPreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = VideoPreviewView(this)

        setContent {
            EditorScreen(previewView)
        }

        val uri = Uri.parse("android.resource://${packageName}/${R.raw.sample}")

        previewView.loadVideo(uri)
    }
}

@Composable
fun EditorScreen(
    previewView: VideoPreviewView
) {
    var brightness by remember { mutableFloatStateOf(0f) }

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
            text = "Brightness: $brightness",
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )

        Slider(
            value = brightness,
            onValueChange = {
                brightness = it
                previewView.setBrightness(brightness)
            },
            valueRange = -1f..1f,
            modifier = Modifier.padding(16.dp)
        )
    }
}
