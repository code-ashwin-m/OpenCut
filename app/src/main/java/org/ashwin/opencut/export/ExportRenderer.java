package org.ashwin.opencut.export;
import android.opengl.GLES20;

import org.ashwin.opencut.core.EffectSettings;
import org.ashwin.opencut.ui.VideoShader;

public class ExportRenderer {
    private int width;

    private int height;

    private VideoShader shader;
    private final float[] mvpMatrix =
            new float[16];
    private EffectSettings effectSettings;

    public ExportRenderer() {

    }

    public void init() {

        shader = new VideoShader();

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
//
//        GLES20.glClearColor(
//                0f,
//                0f,
//                0f,
//                1f
//        );

        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
        );

        shader.draw(
                textureId,
                effectSettings,
                mvpMatrix
        );
    }

    public void release() {

        if (shader != null) {
            shader.release();
            shader = null;
        }
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
    }
}
