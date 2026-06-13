package org.ashwin.opencut.export;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import org.ashwin.opencut.core.EffectSettings;

import java.nio.ByteBuffer;

public class VideoExporter {
    private final Context context;
    private boolean inputDone = false;
    private boolean decoderDone = false;
    private boolean encoderDone = false;
    private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private MediaMuxer muxer;
    private int muxerTrackIndex = -1;
    private boolean muxerStarted = false;
    private float lastProgress = 0f;


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

            try {

                doExport(
                        input,
                        output,
                        effects,
                        callback
                );

            } catch (Exception e) {

                callback.onError(e);
            }

        }).start();
    }

    private void doExport(
            Uri inputUri,
            String outputPath,
            EffectSettings effects,
            ExportCallback callback
    ) throws Exception {

        inputDone = false;
        decoderDone = false;
        encoderDone = false;

        muxerStarted = false;
        muxerTrackIndex = -1;
        lastProgress = 0f;

        MediaExtractor extractor =
                new MediaExtractor();

        MediaExtractor audioExtractor =
                new MediaExtractor();

        extractor.setDataSource(
                context,
                inputUri,
                null
        );

        audioExtractor.setDataSource(
                context,
                inputUri,
                null
        );

        // INPUT VIDEO

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

        // Professional pipeline starts here:
        //
        // InputSurface
        // OutputSurface
        // EGL Context
        // OpenGL Renderer
        // Decoder Loop
        // Encoder Loop
        // MediaMuxer
        //
        // omitted here

        EglCore eglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);

        InputSurface inputSurface = new InputSurface(eglCore, encoder.createInputSurface());

        inputSurface.makeCurrent();

        OutputSurface outputSurface = new OutputSurface(eglCore, effects, width, height);

        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0);

        decoder.start();
        Log.d("EXPORT", "decoder started");

        encoder.start();
        Log.d("EXPORT", "encoder started");

        muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        while (!encoderDone) {
            feedDecoderInput(decoder, extractor);

            drainDecoderOutput(decoder, encoder, inputSurface, outputSurface);

            drainEncoderOutput(encoder, totalDurationUs, callback);
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
        if (!inputDone) {

            int inputBufferId = decoder.dequeueInputBuffer(10000);

            if (inputBufferId >= 0) {

                ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);

                int sampleSize = extractor.readSampleData(inputBuffer, 0);

                if (sampleSize < 0) {

                    decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    inputDone = true;

                } else {

                    long pts = extractor.getSampleTime();

                    decoder.queueInputBuffer(inputBufferId, 0, sampleSize, pts, 0);

                    extractor.advance();
                }
            }
        }
    }

    private void drainDecoderOutput(MediaCodec decoder, MediaCodec encoder, InputSurface inputSurface, OutputSurface outputSurface) {
        if (!decoderDone) {

            int decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, 10000);

            if (decoderStatus >= 0) {

                boolean doRender = bufferInfo.size > 0;

                decoder.releaseOutputBuffer(decoderStatus, doRender);

                if (doRender) {

                    outputSurface.awaitNewImage();

                    outputSurface.drawImage();

                    inputSurface.setPresentationTime(outputSurface.getTimestamp());

                    inputSurface.swapBuffers();
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                    encoder.signalEndOfInputStream();

                    decoderDone = true;
                }
            }
        }

    }

    private void drainEncoderOutput(MediaCodec encoder, long totalDurationUs, ExportCallback callback) {
        while (true) {

            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 0);

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }

            if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                Log.d("EXPORT", "INFO_OUTPUT_FORMAT_CHANGED muxer started");

                MediaFormat videoFormat = encoder.getOutputFormat();

                muxerTrackIndex = muxer.addTrack(videoFormat);

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

            if (bufferInfo.size > 0) {

                encodedData.position(bufferInfo.offset);

                encodedData.limit(bufferInfo.offset + bufferInfo.size);

                muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo);

                float progress = bufferInfo.presentationTimeUs / (float) totalDurationUs;

                if (progress - lastProgress > 0.01f) {

                    lastProgress = progress;

                    callback.onProgress(progress);
                }

                Log.d("EXPORT", "muxer write size=" + bufferInfo.size);
            }

            encoder.releaseOutputBuffer(encoderStatus, false);

            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {

                encoderDone = true;

                break;
            }
        }
    }
}