package org.ashwin.opencut;


import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEngine {

    static {
        System.loadLibrary("opencut");
    }

    // Native C++ Methods
    private native void nativeInitEGL(Surface surface);
    private native int nativeGenerateOESTexture();
    private native void nativeDrawFrame();
    private native void nativeUpdateViewport(int width, int height);
    private native void nativeRelease();

    private Surface renderSurface;
    private SurfaceTexture videoSurfaceTexture;
    private Surface videoDecoderSurface;

    private MediaExtractor videoExtractor;
    private MediaExtractor audioExtractor;
    private MediaCodec videoCodec;
    private MediaCodec audioCodec;
    private AudioTrack audioTrack;

    private boolean isPlaying = false;
    private Thread decodeThread;

    public void setSurface(Surface surface) {
        this.renderSurface = surface;
        // 1. Initialize EGL Context in C++
        nativeInitEGL(surface);

        // 2. Generate an OES texture ID from C++ for the VideoDecoder to render into
        int textureId = nativeGenerateOESTexture();

        // 3. Create a Java SurfaceTexture and Surface linked to the OpenGL texture
        videoSurfaceTexture = new SurfaceTexture(textureId);
        videoSurfaceTexture.setOnFrameAvailableListener(st -> {
            // When a new frame is decoded, update texture and tell C++ to draw
            st.updateTexImage();
            nativeDrawFrame();
        });
        videoDecoderSurface = new Surface(videoSurfaceTexture);
    }

    public void updateViewport(int width, int height) {
        nativeUpdateViewport(width, height);
    }

    public void setDataSource(Context context, Uri uri) {
        try {
            // Setup Video Pipeline
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(context, uri, null);
            int videoTrackIndex = selectTrack(videoExtractor, "video/");
            if (videoTrackIndex >= 0) {
                videoExtractor.selectTrack(videoTrackIndex);
                MediaFormat format = videoExtractor.getTrackFormat(videoTrackIndex);
                videoCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                // Crucial: Pass our custom OpenGL-backed Surface to the codec
                videoCodec.configure(format, videoDecoderSurface, null, 0);
            }

            // Setup Audio Pipeline
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(context, uri, null);
            int audioTrackIndex = selectTrack(audioExtractor, "audio/");
            if (audioTrackIndex >= 0) {
                audioExtractor.selectTrack(audioTrackIndex);
                MediaFormat format = audioExtractor.getTrackFormat(audioTrackIndex);
                audioCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                audioCodec.configure(format, null, null, 0);

                int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                int channelConfig = channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT, minBufferSize, AudioTrack.MODE_STREAM);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void play() {
        if (!isPlaying) {
            isPlaying = true;
            if (videoCodec != null) videoCodec.start();
            if (audioCodec != null) audioCodec.start();
            if (audioTrack != null) audioTrack.play();
            startPlaybackThread();
        }
    }

    public void pause() {
        isPlaying = false;
        if (audioTrack != null) audioTrack.pause();
    }

    private void startPlaybackThread() {
        decodeThread = new Thread(() -> {
            MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
            boolean videoEOS = false;

            // Audio sync timing variables
            long startMs = System.currentTimeMillis();

            while (isPlaying) {
                // NOTE: In a production app, audio and video extraction/decoding
                // should run on separate threads or use asynchronous MediaCodec callbacks.

                // --- Video Decoding Chunk ---
                if (!videoEOS) {
                    int inIndex = videoCodec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = videoCodec.getInputBuffer(inIndex);
                        int sampleSize = videoExtractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            videoCodec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            videoEOS = true;
                        } else {
                            videoCodec.queueInputBuffer(inIndex, 0, sampleSize, videoExtractor.getSampleTime(), 0);
                            videoExtractor.advance();
                        }
                    }
                }

                int outIndex = videoCodec.dequeueOutputBuffer(videoInfo, 10000);
                if (outIndex >= 0) {
                    // A/V SYNCHRONIZATION LOGIC (Simplified)
                    // We calculate where we *should* be based on system time (or AudioTrack head position)
                    long timeDelta = System.currentTimeMillis() - startMs;
                    long presentationTimeMs = videoInfo.presentationTimeUs / 1000;

                    // If frame is early, sleep to sync
                    while (presentationTimeMs > timeDelta && isPlaying) {
                        try { Thread.sleep(10); } catch (InterruptedException e) {}
                        timeDelta = System.currentTimeMillis() - startMs;
                    }

                    // Release frame to SurfaceTexture (true triggers updateTexImage in listener)
                    videoCodec.releaseOutputBuffer(outIndex, true);
                }

                // --- Audio Decoding Chunk (Omitted for brevity, follows similar dequeue/queue pattern
                // passing decoded PCM bytebuffers to audioTrack.write()) ---
            }
        });
        decodeThread.start();
    }

    private int selectTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    public void releaseSurface() {
        nativeRelease();
    }

    public void release() {
        isPlaying = false;
        if (videoCodec != null) { videoCodec.stop(); videoCodec.release(); }
        if (audioCodec != null) { audioCodec.stop(); audioCodec.release(); }
        if (videoExtractor != null) videoExtractor.release();
        if (audioExtractor != null) audioExtractor.release();
        if (audioTrack != null) audioTrack.release();
    }
}