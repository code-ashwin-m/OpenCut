package org.ashwin.opencut.data.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;

import org.ashwin.opencut.domain.model.EffectSettings;
import org.ashwin.opencut.data.media.GLUtils;

public class OutputSurface implements SurfaceTexture.OnFrameAvailableListener{
    private SurfaceTexture surfaceTexture;

    private Surface surface;

    private int textureId;

    private boolean frameAvailable;

    private final Object frameSyncObject =
            new Object();

    private final ExportRenderer renderer;

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (frameSyncObject) {

            frameAvailable = true;

            frameSyncObject.notifyAll();
        }
    }

    public OutputSurface(
            Context context,
            EglCore eglCore,
            EffectSettings effects,
            int width,
            int height
    ){

        textureId = GLUtils.createOESTexture();

        surfaceTexture =
                new SurfaceTexture(textureId);

        surfaceTexture.setOnFrameAvailableListener(
                this
        );

        surface =
                new Surface(surfaceTexture);

        renderer = new ExportRenderer(context);

        renderer.init();

        renderer.setOutputSize(
                width,
                height
        );

        renderer.setEffects(
                effects
        );
    }


    public Surface getSurface() {
        return surface;
    }

    public int getTextureId() {
        return textureId;
    }

    public void awaitNewImage() {

        synchronized (frameSyncObject) {

            while (!frameAvailable) {

                try {

                    frameSyncObject.wait(500);

                } catch (
                        InterruptedException e
                ) {

                    throw new RuntimeException(e);
                }
            }

            frameAvailable = false;
        }

        surfaceTexture.updateTexImage();
    }

    public void drawImage() {
        Log.d(
                "EXPORT",
                "drawImage"
        );

        renderer.renderFrame(
                textureId
        );
    }

    public long getTimestamp() {

        return surfaceTexture.getTimestamp();
    }

    public void release() {

        if (surface != null) {
            surface.release();
            surface = null;
        }

        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }

        renderer.release();
    }

}
