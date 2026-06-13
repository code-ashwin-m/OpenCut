package org.ashwin.opencut.platform.nativebridge

object NativeEngineBridge {
    init {
        System.loadLibrary("opencut")
    }

    external fun nativeEngineInfo(): String
    external fun nativePrepareEngine()
    external fun nativeReleaseEngine()

    external fun nativeInitRenderer()
    external fun nativeSetEffects(
        brightness: Float,
        contrast: Float,
        exposure: Float,
        highlights: Float,
        shadows: Float
    )
    external fun nativeDrawFrame(textureId: Int, mvpMatrix: FloatArray)
    external fun nativeReleaseRenderer()
}
