package org.ashwin.opencut.presentation.preview

import android.content.Context
import android.net.Uri
import android.opengl.GLSurfaceView
import org.ashwin.opencut.data.media.PreviewRenderer
import org.ashwin.opencut.domain.model.EffectSettings

class VideoPreviewView(context: Context) : GLSurfaceView(context) {

    private val renderer: PreviewRenderer = PreviewRenderer(context)

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun loadVideo(uri: Uri?) {
        renderer.loadVideo(uri)
    }

    fun setEffect(effectSettings: EffectSettings) {
        renderer.setEffect(effectSettings)
    }

    fun togglePlayback() {
        renderer.togglePlayback()
    }

    fun isPlaying(): Boolean {
        return renderer.isPlaying
    }
}
