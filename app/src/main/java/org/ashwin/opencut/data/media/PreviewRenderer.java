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

    private VideoShader videoShader;

    private boolean frameAvailable = false;

    private final float[] mvpMatrix =
            new float[16];

    private int videoWidth;
    private int videoHeight;
    private EffectSettings effectSettings;

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

        videoShader = new VideoShader(context);

        initPlayer();

        if (pendingVideoUri != null) {
            playVideo(pendingVideoUri);
            pendingVideoUri = null;
        }
    }


    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0,0,width,height);

        Matrix.setIdentityM(
                mvpMatrix,
                0
        );

        if(videoWidth == 0 || videoHeight == 0)
            return;

        float videoAspect =
                (float) videoWidth /
                        videoHeight;

        float viewAspect =
                (float) width /
                        height;

        if(videoAspect > viewAspect){

            float scaleY =
                    viewAspect /
                            videoAspect;

            Matrix.scaleM(
                    mvpMatrix,
                    0,
                    1f,
                    scaleY,
                    1f
            );

        } else {

            float scaleX =
                    videoAspect /
                            viewAspect;

            Matrix.scaleM(
                    mvpMatrix,
                    0,
                    scaleX,
                    1f,
                    1f
            );
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
        );

        if (frameAvailable) {

            surfaceTexture.updateTexImage();

            frameAvailable = false;
        }

        videoShader.draw(textureId, effectSettings, mvpMatrix);
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

            videoWidth =
                    mediaPlayer.getVideoWidth();

            videoHeight =
                    mediaPlayer.getVideoHeight();

            mediaPlayer.setLooping(true);

            mediaPlayer.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setEffect(EffectSettings effectSettings) {
        this.effectSettings = effectSettings;
    }

    private void initPlayer() {
        if (mediaPlayer != null)
            return;

        mediaPlayer = new MediaPlayer();

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

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }
}
