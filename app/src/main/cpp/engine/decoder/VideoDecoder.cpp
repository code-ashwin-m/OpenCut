//
// Created by Ashwin M on 15/06/26.
//

#include "VideoDecoder.h"
#include <android/log.h>
#include <unistd.h>

#define LOG_TAG "NativeDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

VideoDecoder::VideoDecoder() :
        mExtractor(nullptr),
        mCodec(nullptr),
        mOesTextureId(0),
        mVideoTrackIndex(-1),
        mVideoWidth(0),
        mVideoHeight(0),
        mDurationUs(0),
        mIsExtractorEof(false),
        mIsCodecEof(false) {}

VideoDecoder::~VideoDecoder() {
    Release();
}

bool VideoDecoder::Prepare(const std::string& filePath, GLuint oesTextureId) {
    std::lock_guard<std::mutex> lock(mDecoderMutex);
    mFilePath = filePath;
    mOesTextureId = oesTextureId;

    mExtractor = AMediaExtractor_new();
    media_status_t status = AMediaExtractor_setDataSource(mExtractor, mFilePath.c_str());
    if (status != AMEDIA_OK) {
        LOGE("Failed to set data source for path: %s, error: %d", mFilePath.c_str(), status);
        return false;
    }

    size_t numTracks = AMediaExtractor_getTrackCount(mExtractor);
    const char* mime = nullptr;

    for (size_t i = 0; i < numTracks; ++i) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(mExtractor, i);
        AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime != nullptr && strncmp(mime, "video/", 6) == 0) {
            mVideoTrackIndex = i;
            AMediaExtractor_selectTrack(mExtractor, i);

            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &mVideoWidth);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &mVideoHeight);
            AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &mDurationUs);

            LOGI("Found Video Track [%d]: Mime=%s, Size=%dx%d, Duration=%lld ms",
                 mVideoTrackIndex, mime, mVideoWidth, mVideoHeight, mDurationUs / 1000);

            AMediaFormat_delete(format);
            break;
        }
        AMediaFormat_delete(format);
    }

    if (mVideoTrackIndex == -1) {
        LOGE("No valid video tracks found in file: %s", mFilePath.c_str());
        return false;
    }

    return SetupCodec(mime);
}

bool VideoDecoder::SetupCodec(const char* mimeType) {
    // Create decoder from native library registry
    mCodec = AMediaCodec_createDecoderByType(mimeType);
    if (!mCodec) {
        LOGE("Failed to create native hardware decoder for mime: %s", mimeType);
        return false;
    }

    AMediaFormat* format = AMediaExtractor_getTrackFormat(mExtractor, mVideoTrackIndex);

    // CRITICAL SPEED FIX: Configure the codec to render outputs directly into the target
    // OpenGL texture surface coordinate system (No slow CPU buffer copy/allocations)
    media_status_t status = AMediaCodec_configure(mCodec, format, nullptr, nullptr, 0);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        AMediaFormat_delete(format);
        return false;
    }

    status = AMediaCodec_start(mCodec);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        AMediaFormat_delete(format);
        return false;
    }

    AMediaFormat_delete(format);
    mIsExtractorEof = false;
    mIsCodecEof = false;
    LOGI("Hardware Decoder successfully initialized and started.");
    return true;
}

bool VideoDecoder::DecodeNextFrame(int64_t targetPresentationTimeUs) {
    std::lock_guard<std::mutex> lock(mDecoderMutex);
    if (mCodec == nullptr || mExtractor == nullptr) return false;

    const int64_t timeoutUs = 2000; // 2 milliseconds timeout for low latency scrubbing
    int retryCount = 0;
    const int maxRetries = 150;     // Prevent deadlock when pipeline is stalling

    while (!mIsCodecEof && retryCount++ < maxRetries) {
        // --- 1. Feed Compressed Packets into Hardware Decoder Queue ---
        if (!mIsExtractorEof) {
            ssize_t inputBufIndex = AMediaCodec_dequeueInputBuffer(mCodec, timeoutUs);
            if (inputBufIndex >= 0) {
                size_t bufferSize;
                uint8_t* inputBuffer = AMediaCodec_getInputBuffer(mCodec, inputBufIndex, &bufferSize);

                ssize_t sampleSize = AMediaExtractor_readSampleData(mExtractor, inputBuffer, bufferSize);
                if (sampleSize < 0) {
                    // Send End of Stream (EOS) signal down the pipeline
                    AMediaCodec_queueInputBuffer(mCodec, inputBufIndex, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    mIsExtractorEof = true;
                    LOGI("Extractor reached End of Stream.");
                } else {
                    int64_t presentationTimeUs = AMediaExtractor_getSampleTime(mExtractor);
                    AMediaCodec_queueInputBuffer(mCodec, inputBufIndex, 0, sampleSize, presentationTimeUs, 0);
                    AMediaExtractor_advance(mExtractor);
                }
            }
        }

        // --- 2. Dequeue Decoded Frames from Hardware Decoder Output ---
        AMediaCodecBufferInfo info;
        ssize_t outputBufIndex = AMediaCodec_dequeueOutputBuffer(mCodec, &info, timeoutUs);

        if (outputBufIndex >= 0) {
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                mIsCodecEof = true;
                LOGI("Codec output reached End of Stream.");
            }

            // Verify if frame is close to or matching our target rendering timestamp
            bool renderFrame = (info.presentationTimeUs >= targetPresentationTimeUs);

            // True triggers direct GL draw execution in OpenGL thread surface mapping
            AMediaCodec_releaseOutputBuffer(mCodec, outputBufIndex, renderFrame);

            if (renderFrame) {
                // Success! Frame was parsed, decoded, and rendered to texture surface.
                return true;
            }
        } else if (outputBufIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* format = AMediaCodec_getOutputFormat(mCodec);
            LOGI("Decoder Output Format changed: %s", AMediaFormat_toString(format));
            AMediaFormat_delete(format);
        } else if (outputBufIndex == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            LOGI("Decoder Output buffers changed.");
        }
    }

    return false;
}

bool VideoDecoder::SeekTo(int64_t timeUs) {
    std::lock_guard<std::mutex> lock(mDecoderMutex);
    if (mExtractor == nullptr || mCodec == nullptr) return false;

    LOGI("Seeking decoder to: %lld ms", timeUs / 1000);

    // Seek extractor to the nearest prior keyframe (I-Frame)
    media_status_t status = AMediaExtractor_seekTo(mExtractor, timeUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    if (status != AMEDIA_OK) {
        LOGE("Extractor seek failed: %d", status);
        return false;
    }

    FlushBuffers();

    // Reset pipeline state
    mIsExtractorEof = false;
    mIsCodecEof = false;

    // Fast-forward decode loop until we catch up to the target timestamp
    return DecodeNextFrame(timeUs);
}

void VideoDecoder::FlushBuffers() {
    if (mCodec != nullptr) {
        AMediaCodec_flush(mCodec);
    }
}

void VideoDecoder::Release() {
    std::lock_guard<std::mutex> lock(mDecoderMutex);
    if (mCodec != nullptr) {
        AMediaCodec_stop(mCodec);
        AMediaCodec_delete(mCodec);
        mCodec = nullptr;
    }
    if (mExtractor != nullptr) {
        AMediaExtractor_delete(mExtractor);
        mExtractor = nullptr;
    }
    mVideoTrackIndex = -1;
    mIsExtractorEof = false;
    mIsCodecEof = false;
    LOGI("Decoder resources fully released.");
}


