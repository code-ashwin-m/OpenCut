package org.ashwin.opencut


import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val nativeEngine = NativeEngine()
    private var isPlaying by mutableStateOf(false)
    private var videoLoaded by mutableStateOf(false)

    // Hold onto the FD to prevent it from being garbage collected while C++ uses it
    private var currentFd: ParcelFileDescriptor? = null

    private val selectVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->

        uri?.let {
            try {
                // Get a File Descriptor from the content URI
                currentFd?.close()
                currentFd = contentResolver.openFileDescriptor(it, "r")

                currentFd?.let { pfd ->
                    // Pass the raw integer file descriptor to C++
                    nativeEngine.setDataSource(pfd.fd)
                    videoLoaded = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditorUI()
                }
            }
        }
    }

    @Composable
    fun EditorUI() {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    nativeEngine.setSurface(holder.surface)
                                }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    nativeEngine.releaseSurface()
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { selectVideoLauncher.launch("video/*") }) {
                    Text("Select Video")
                }

                Button(
                    onClick = {
                        if (isPlaying) nativeEngine.pause() else nativeEngine.play()
                        isPlaying = !isPlaying
                    },
                    enabled = videoLoaded
                ) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nativeEngine.release()
        currentFd?.close()
    }
}

// Thin JNI Wrapper
class NativeEngine {
    init {
        System.loadLibrary("opencut")
    }

    external fun setSurface(surface: Surface)
    external fun releaseSurface()
    external fun setDataSource(fd: Int) // Passing the FD instead of a Path!
    external fun play()
    external fun pause()
    external fun release()
}