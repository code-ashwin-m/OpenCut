package org.ashwin.opencut.engine.render

import android.view.Surface
import org.ashwin.opencut.engine.timeline.TimelineComposition

class VideoRenderBridge {

    // Native pointer to our C++ Engine instance
    private var nativeEngineHandle: Long = 0

    init {
        System.loadLibrary("opencut")
    }

    /**
     * Initializes the native rendering engine.
     */
    external fun nativeInit(): Long

    /**
     * Releases the native rendering engine.
     */
    external fun nativeRelease(handle: Long)

    /**
     * Passes the surface (from TextureView/GLSurfaceView) to OpenGL.
     */
    external fun nativeSetSurface(handle: Long, surface: Surface?)

    /**
     * Updates the native engine with the latest immutable timeline state.
     * Serialization should be done efficiently (e.g. Protocol Buffers, FlatBuffers, or manual parsing).
     */
    external fun nativeUpdateComposition(handle: Long, compositionJson: String)

    /**
     * Seeks playback to a specific timestamp in milliseconds.
     */
    external fun nativeSeekTo(handle: Long, positionMs: Long)

    /**
     * Start/Stop playback loop.
     */
    external fun nativeSetPlaying(handle: Long, isPlaying: Boolean)
}
