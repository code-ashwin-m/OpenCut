package org.ashwin.opencut.ui;

import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import org.jetbrains.annotations.Nullable;

public class VideoPreviewView extends GLSurfaceView {

    private final PreviewRenderer renderer;

    public VideoPreviewView(Context context) {
        super(context);

        setEGLContextClientVersion(2);

        renderer = new PreviewRenderer(context);

        setRenderer(renderer);

        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    public void loadVideo(@Nullable Uri uri) {
        renderer.loadVideo(uri);
    }

    public void setBrightness(float value){
        renderer.setBrightness(value);
    }
}
