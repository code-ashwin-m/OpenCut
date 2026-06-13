package org.ashwin.opencut.data.media;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import org.ashwin.opencut.domain.model.EffectSettings;

import java.nio.ByteBuffer;

public class VideoExporter {
    // GLOBAL
    private final Context context;
    private MediaMuxer muxer;
    private boolean muxerStarted = false;
    private float lastProgress = 0f;

    // VIDEO
    private boolean videoDecoderDone = false;
    private boolean videoEncoderDone = false;
    private int muxerVideoTrackIndex = -1;
    private boolean videoDone = false;
    private MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();

    // AUDIO
    private int muxerAudioTrackIndex = -1;
    private boolean audioDone = false;
    private ByteBuffer audioBuffer = ByteBuffer.allocateDirect(1024 * 1024);
    private MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();

    public VideoExporter(Context context) {
        this.context = context;
    }

    public void export(
            Uri input,
            String output,
            EffectSettings effects,
            ExportCallback callback
    ) {
        new Thread(() -> {
            exportSync(input, output, effects, callback);
        }).start();
    }

    public void exportSync(
            Uri input,
            String output,
            EffectSettings effects,
            ExportCallback callback
    ) {
        try {
            doExport(input, output, effects, callback);
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private void doExport(
            Uri inputUri,
            String outputPath,
            EffectSettings effects,
            ExportCallback callback
    ) throws Exception {

        // INPUT VIDEO
        videoDone = false;
        videoDecoderDone = false;
        videoEncoderDone = false;

        muxerStarted = false;
        muxerVideoTrackIndex = -1;
        lastProgress = 0f;

        MediaExtractor extractor =
                new MediaExtractor();


        extractor.setDataSource(
                context,
                inputUri,
                null
        );

        int videoTrack = findVideoTrack(extractor);
        extractor.selectTrack(videoTrack);

        MediaFormat inputFormat =
                extractor.getTrackFormat(videoTrack);

        int width =
                inputFormat.getInteger(
                        MediaFormat.KEY_WIDTH
                );

        int height =
                inputFormat.getInteger(
                        MediaFormat.KEY_HEIGHT
                );

        long totalDurationUs =
                inputFormat.getLong(
                        MediaFormat.KEY_DURATION
                );

        String mime =
                inputFormat.getString(
                        MediaFormat.KEY_MIME
                );

        MediaCodec decoder =
                MediaCodec.createDecoderByType(
                        mime
                );

        // INPUT AUDIO

        audioDone = false;

        MediaExtractor audioExtractor = new MediaExtractor();

        audioExtractor.setDataSource(context, inputUri, null);

        int audioTrack = findAudioTrack(audioExtractor);

        MediaFormat audioFormat = null;

        if (audioTrack >= 0) {

            audioExtractor.selectTrack(audioTrack);

            audioFormat = audioExtractor.getTrackFormat(audioTrack);
        }
        

        // OUTPUT

        MediaFormat outputFormat =
                MediaFormat.createVideoFormat(
                        "video/avc",
                        width,
                        height
                );

        outputFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                8_000_000
        );

        outputFormat.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                30
        );

        outputFormat.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                1
        );

        outputFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatSurface
        );

        MediaCodec encoder =
                MediaCodec.createEncoderByType(
                        "video/avc"
                );

        encoder.configure(
                outputFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        EglCore eglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);

        InputSurface inputSurface = new InputSurface(eglCore, encoder.createInputSurface());

        inputSurface.makeCurrent();

        OutputSurface outputSurface = new OutputSurface(context, eglCore, effects, width, height);

        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);

        decoder.start();
        Log.d("EXPORT", "decoder started");

        encoder.start();
        Log.d("EXPORT", "encoder started");

        muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        if (audioTrack >= 0) {
            muxerAudioTrackIndex = muxer.addTrack(audioFormat);
            Log.d("EXPORT", "Audio track added: " + muxerAudioTrackIndex);
        }

        while (!videoDone || !audioDone) {
            feedDecoderInput(decoder, extractor);

            drainDecoderOutput(decoder, encoder, inputSurface, outputSurface);

            drainEncoderOutput(encoder, totalDurationUs, callback);

            drainAudioTrack(audioExtractor);
        }

        if (muxerStarted) {
            muxer.stop();
            muxer.release();
        }

        decoder.stop();
        decoder.release();

        encoder.stop();
        encoder.release();

        inputSurface.release();
        outputSurface.release();

        extractor.release();
        audioExtractor.release();

        callback.onSuccess(outputPath);
    }

    private int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {

            MediaFormat format = extractor.getTrackFormat(i);

            String mime = format.getString(MediaFormat.KEY_MIME);

            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }

        throw new RuntimeException("No video track found");
    }

    private int findAudioTrack(MediaExtractor extractor) {

        for (int i = 0; i < extractor.getTrackCount(); i++) {

            MediaFormat format = extractor.getTrackFormat(i);

            String mime = format.getString(MediaFormat.KEY_MIME);

            if (mime != null && mime.startsWith("audio/")) {

                return i;
            }
        }

        return -1;
    }

    private void feedDecoderInput(MediaCodec decoder, MediaExtractor extractor) {
        if (!videoDone) {

            int inputBufferId = decoder.dequeueInputBuffer(10000);

            if (inputBufferId >= 0) {

                ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);

                int sampleSize = extractor.readSampleData(inputBuffer, 0);

                if (sampleSize < 0) {

                    decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    videoDone = true;

                } else {

                    long pts = extractor.getSampleTime();

                    decoder.queueInputBuffer(inputBufferId, 0, sampleSize, pts, 0);

                    extractor.advance();
                }
            }
        }
    }

    private void drainDecoderOutput(MediaCodec decoder, MediaCodec encoder, InputSurface inputSurface, OutputSurface outputSurface) {
        if (!videoDecoderDone) {

            int decoderStatus = decoder.dequeueOutputBuffer(videoBufferInfo, 10000);

            if (decoderStatus >= 0) {

                boolean doRender = videoBufferInfo.size > 0;

                decoder.releaseOutputBuffer(decoderStatus, doRender);

                if (doRender) {

                    outputSurface.awaitNewImage();

                    outputSurface.drawImage();

                    inputSurface.setPresentationTime(outputSurface.getTimestamp());

                    inputSurface.swapBuffers();
                }

                if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                    encoder.signalEndOfInputStream();

                    videoDecoderDone = true;
                }
            }
        }

    }

    private void drainEncoderOutput(MediaCodec encoder, long totalDurationUs, ExportCallback callback) {
        while (true) {

            int encoderStatus = encoder.dequeueOutputBuffer(videoBufferInfo, 0);

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }

            if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                Log.d("EXPORT", "INFO_OUTPUT_FORMAT_CHANGED muxer started");

                MediaFormat videoFormat = encoder.getOutputFormat();

                muxerVideoTrackIndex = muxer.addTrack(videoFormat);

                muxer.start();

                muxerStarted = true;

                continue;
            }

            if (encoderStatus < 0) {
                continue;
            }

            ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);

            if (encodedData == null) {

                throw new RuntimeException("encoderOutputBuffer null");
            }

            if (videoBufferInfo.size > 0) {

                encodedData.position(videoBufferInfo.offset);

                encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size);

                muxer.writeSampleData(muxerVideoTrackIndex, encodedData, videoBufferInfo);

                float progress = videoBufferInfo.presentationTimeUs / (float) totalDurationUs;

                if (progress - lastProgress > 0.01f) {

                    lastProgress = progress;

                    callback.onProgress(progress);
                }

                Log.d("EXPORT", "muxer write size=" + videoBufferInfo.size);
            }

            encoder.releaseOutputBuffer(encoderStatus, false);

            if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                videoEncoderDone = true;

                break;
            }
        }
    }

    private void drainAudioTrack(MediaExtractor audioExtractor) {

        if (!muxerStarted)
            return;

        if (audioDone)
            return;

        audioBuffer.clear();

        int sampleSize = audioExtractor.readSampleData(audioBuffer, 0);

        if (sampleSize < 0) {

            audioDone = true;
            return;
        }

        audioBufferInfo.offset = 0;
        audioBufferInfo.size = sampleSize;

        audioBufferInfo.presentationTimeUs =
                audioExtractor.getSampleTime();

        audioBufferInfo.flags =
                audioExtractor.getSampleFlags();

        muxer.writeSampleData(
                muxerAudioTrackIndex,
                audioBuffer,
                audioBufferInfo
        );

        audioExtractor.advance();
    }
}