package org.ashwin.opencut.data.media;
import android.content.Context;
import android.opengl.GLES20;

import org.ashwin.opencut.domain.model.EffectSettings;
import org.ashwin.opencut.platform.nativebridge.NativeEngineBridge;

public class ExportRenderer {
    private int width;

    private int height;

    // VideoShader is now implemented native-side in C++
    private final float[] mvpMatrix =
            new float[16];
    private EffectSettings effectSettings = new EffectSettings();

    private Context context;

    public ExportRenderer(Context context) {
        this.context = context;
    }

    public void init() {
        if (effectSettings != null) {
            NativeEngineBridge.INSTANCE.nativeSetEffects(
                effectSettings.brightness,
                effectSettings.contrast,
                effectSettings.exposure,
                effectSettings.highlights,
                effectSettings.shadows
            );
        }
        NativeEngineBridge.INSTANCE.nativeInitRenderer();

        android.opengl.Matrix.setIdentityM(
                mvpMatrix,
                0
        );
    }

    public void renderFrame(
            int textureId
    ) {

        GLES20.glViewport(
                0,
                0,
                width,
                height
        );

        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
        );

        if (effectSettings != null) {
            NativeEngineBridge.INSTANCE.nativeSetEffects(
                effectSettings.brightness,
                effectSettings.contrast,
                effectSettings.exposure,
                effectSettings.highlights,
                effectSettings.shadows
            );
        }
        NativeEngineBridge.INSTANCE.nativeDrawFrame(textureId, mvpMatrix);
    }

    public void release() {
        NativeEngineBridge.INSTANCE.nativeReleaseRenderer();
    }


    public void setOutputSize(
            int width,
            int height
    ) {

        this.width = width;
        this.height = height;
    }

    private int getWidth() {
        return width;
    }

    private int getHeight() {
        return height;
    }

    public void setEffects(EffectSettings effects) {
        this.effectSettings = effects;
        if (effects != null) {
            NativeEngineBridge.INSTANCE.nativeSetEffects(
                effects.brightness,
                effects.contrast,
                effects.exposure,
                effects.highlights,
                effects.shadows
            );
        }
    }
}
