package org.ashwin.opencut.data.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import org.ashwin.opencut.domain.model.EffectSettings;
import org.ashwin.opencut.platform.nativebridge.NativeEngineBridge;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PreviewRenderer
        implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    private final Context context;
    private SurfaceTexture surfaceTexture;
    private int textureId;

    private MediaPlayer mediaPlayer;

    private Uri pendingVideoUri;

    // VideoShader is now implemented native-side in C++

    private boolean frameAvailable = false;

    private final float[] mvpMatrix =
            new float[16];

    private int videoWidth;
    private int videoHeight;
    private int surfaceWidth;
    private int surfaceHeight;
    private boolean aspectNeedsUpdate = false;
    private EffectSettings effectSettings = new EffectSettings();

    public PreviewRenderer(Context context) {
        this.context = context;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        frameAvailable = true;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        textureId = GLUtils.createOESTexture();

        surfaceTexture = new SurfaceTexture(textureId);

        surfaceTexture.setOnFrameAvailableListener(this);

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

        initPlayer();

        if (pendingVideoUri != null) {
            playVideo(pendingVideoUri);
            pendingVideoUri = null;
        }
    }


    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        this.surfaceWidth = width;
        this.surfaceHeight = height;
        this.aspectNeedsUpdate = true;
    }

    private void updateViewportMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0);

        if (videoWidth == 0 || videoHeight == 0 || surfaceWidth == 0 || surfaceHeight == 0)
            return;

        float videoAspect = (float) videoWidth / videoHeight;
        float viewAspect = (float) surfaceWidth / surfaceHeight;

        if (videoAspect > viewAspect) {
            float scaleY = viewAspect / videoAspect;
            Matrix.scaleM(mvpMatrix, 0, 1f, scaleY, 1f);
        } else {
            float scaleX = videoAspect / viewAspect;
            Matrix.scaleM(mvpMatrix, 0, scaleX, 1f, 1f);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
        );

        if (aspectNeedsUpdate) {
            updateViewportMatrix();
            aspectNeedsUpdate = false;
        }

        if (frameAvailable) {
            surfaceTexture.updateTexImage();
            frameAvailable = false;
        }

        NativeEngineBridge.INSTANCE.nativeDrawFrame(textureId, mvpMatrix);
    }

    public void loadVideo(Uri uri) {
        if (mediaPlayer == null) {
            pendingVideoUri = uri;
            return;
        }

        playVideo(uri);
    }

    private void playVideo(Uri uri) {
        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(
                    context,
                    uri
            );
            mediaPlayer.prepare();
            videoWidth = mediaPlayer.getVideoWidth();
            videoHeight = mediaPlayer.getVideoHeight();
            aspectNeedsUpdate = true;
            mediaPlayer.setLooping(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setEffect(EffectSettings effectSettings) {
        this.effectSettings = effectSettings;
        if (effectSettings != null) {
            NativeEngineBridge.INSTANCE.nativeSetEffects(
                    effectSettings.brightness,
                    effectSettings.contrast,
                    effectSettings.exposure,
                    effectSettings.highlights,
                    effectSettings.shadows
            );
        }
    }

    private void initPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
        }

        Surface surface = new Surface(surfaceTexture);
        mediaPlayer.setSurface(surface);
    }

    public void togglePlayback() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
            }
        }
    }

    public void pausePlayback() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public void seekTo(int positionMs) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo(positionMs);
            } catch (IllegalStateException e) {
                Log.e("PreviewRenderer", "Error seeking MediaPlayer: " + e.getMessage());
            }
        }
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                Log.e("PreviewRenderer", "Error getting position: " + e.getMessage());
            }
        }
        return 0;
    }

    public int getDuration() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                Log.e("PreviewRenderer", "Error getting duration: " + e.getMessage());
            }
        }
        return 0;
    }

    public void release() {
        NativeEngineBridge.INSTANCE.nativeReleaseRenderer();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mediaPlayer = null;
        }
    }
}
