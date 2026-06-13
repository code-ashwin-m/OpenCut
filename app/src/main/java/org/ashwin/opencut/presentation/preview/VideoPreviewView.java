package org.ashwin.opencut.presentation.preview;

import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;

import org.ashwin.opencut.data.media.PreviewRenderer;
import org.ashwin.opencut.domain.model.EffectSettings;
import org.jetbrains.annotations.NotNull;
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

    public void setEffect(EffectSettings effectSettings) {
        renderer.setEffect(effectSettings);
    }
}
