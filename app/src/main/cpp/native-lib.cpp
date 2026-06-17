#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <thread>
#include <atomic>
#include <unistd.h>
#include <chrono> // Added for high-precision timing synchronization

#define LOG_TAG "NativeVideoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Native state variables
ANativeWindow* nativeWindow = nullptr;
AMediaExtractor* extractor = nullptr;
AMediaCodec* videoCodec = nullptr;

std::thread decodeThread;
std::atomic<bool> isPlaying(false);
std::atomic<bool> isThreadRunning(false);

// Forward declaration of the decode loop
void decodeLoop();

// Helper to safely clean up old media resources and stop threads
void cleanupMedia() {
    isPlaying = false;
    isThreadRunning = false;

    // Safely stop and join the decoding thread
    if (decodeThread.joinable()) {
        decodeThread.join();
    }

    // Stop and delete the codec
    if (videoCodec) {
        AMediaCodec_stop(videoCodec);
        AMediaCodec_delete(videoCodec);
        videoCodec = nullptr;
    }

    // Delete the extractor
    if (extractor) {
        AMediaExtractor_delete(extractor);
        extractor = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_setSurface(JNIEnv* env, jobject, jobject surface) {
    if (nativeWindow) {
        ANativeWindow_release(nativeWindow);
    }
    nativeWindow = ANativeWindow_fromSurface(env, surface);
    LOGI("Native Surface set.");
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_releaseSurface(JNIEnv*, jobject) {
    if (nativeWindow) {
        ANativeWindow_release(nativeWindow);
        nativeWindow = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_setDataSource(JNIEnv* env, jobject, jint fd) {
    // Clean up previous video resources and threads before loading a new one
    cleanupMedia();

    // 1. Initialize the Native Media Extractor
    extractor = AMediaExtractor_new();

    // Pass the File Descriptor (FD). We use offset 0 and length to the end of the file.
    off64_t offset = 0;
    off64_t length = lseek64(fd, 0, SEEK_END);
    lseek64(fd, 0, SEEK_SET); // reset to beginning

    media_status_t status = AMediaExtractor_setDataSourceFd(extractor, fd, offset, length);
    if (status != AMEDIA_OK) {
        LOGE("Failed to set data source on extractor");
        return;
    }

    // 2. Find the Video Track
    int numTracks = AMediaExtractor_getTrackCount(extractor);
    for (int i = 0; i < numTracks; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor, i);
        const char* mime;
        if (!AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime)) {
            continue;
        }

        if (strncmp(mime, "video/", 6) == 0) {
            AMediaExtractor_selectTrack(extractor, i);

            // 3. Create and configure the Native MediaCodec
            videoCodec = AMediaCodec_createDecoderByType(mime);

            // NOTE: For Phase 1, we pass the Compose NativeWindow directly to the codec.
            // In Phase 2 (Effects), we will instead configure this to an OES Texture linked to OpenGL.
            AMediaCodec_configure(videoCodec, format, nativeWindow, nullptr, 0);
            AMediaFormat_delete(format);
            LOGI("Video Codec Configured with Mime: %s", mime);
            break;
        }
        AMediaFormat_delete(format);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_play(JNIEnv*, jobject) {
    if (!isPlaying && videoCodec != nullptr) {
        AMediaCodec_start(videoCodec);
        isPlaying = true;
        isThreadRunning = true;

        // Spawn a C++ std::thread to handle the continuous decode loop
        decodeThread = std::thread(decodeLoop);
        LOGI("Playback Started");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_pause(JNIEnv*, jobject) {
    isPlaying = false;
    isThreadRunning = false;
    if (decodeThread.joinable()) {
        decodeThread.join();
    }
    LOGI("Playback Paused");
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_release(JNIEnv*, jobject) {
    // Reuse the cleanup logic when destroying the component
    cleanupMedia();
}

// ---------------------------------------------------------
// The C++ heavy-lifting decode thread
// ---------------------------------------------------------
void decodeLoop() {
    bool isEOS = false;

    // Monotonic clock variables for absolute time matching
    auto startTime = std::chrono::steady_clock::now();
    int64_t firstFramePts = -1;
    bool isFirstFrame = true;

    while (isThreadRunning) {
        if (!isEOS) {
            // 1. Get an available input buffer index from the codec
            ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(videoCodec, 10000); // 10ms timeout
            if (bufIdx >= 0) {
                size_t bufSize;
                uint8_t* buf = AMediaCodec_getInputBuffer(videoCodec, bufIdx, &bufSize);

                // 2. Read the chunk from the file via extractor
                ssize_t sampleSize = AMediaExtractor_readSampleData(extractor, buf, bufSize);
                if (sampleSize < 0) {
                    // End of file
                    AMediaCodec_queueInputBuffer(videoCodec, bufIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    isEOS = true;
                } else {
                    int64_t presentationTimeUs = AMediaExtractor_getSampleTime(extractor);
                    // 3. Queue the compressed data into the decoder
                    AMediaCodec_queueInputBuffer(videoCodec, bufIdx, 0, sampleSize, presentationTimeUs, 0);
                    AMediaExtractor_advance(extractor);
                }
            }
        }

        AMediaCodecBufferInfo info;
        // 4. Retrieve decoded frames
        ssize_t status = AMediaCodec_dequeueOutputBuffer(videoCodec, &info, 10000);

        if (status >= 0) {
            int64_t framePts = info.presentationTimeUs;

            if (isFirstFrame) {
                firstFramePts = framePts;
                startTime = std::chrono::steady_clock::now();
                isFirstFrame = false;
            }

            // Real-Time Presentation Sync Calculation
            auto now = std::chrono::steady_clock::now();
            int64_t elapsedRealTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(now - startTime).count();
            int64_t targetElapsedUs = framePts - firstFramePts;

            // If the frame is early, delay the thread to sync playback
            if (targetElapsedUs > elapsedRealTimeUs) {
                int64_t sleepTimeUs = targetElapsedUs - elapsedRealTimeUs;

                // Keep a sanity check (e.g. max 1s sleep) to handle sudden timestamp jumps smoothly
                if (sleepTimeUs < 1000000) {
                    std::this_thread::sleep_for(std::chrono::microseconds(sleepTimeUs));
                }
            }

            // 5. Release and render! Passing 'true' tells Android to paint this frame to the ANativeWindow
            AMediaCodec_releaseOutputBuffer(videoCodec, status, true);

            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                isThreadRunning = false; // Stop at end of video
            }
        } else if (status == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            LOGI("Output buffers changed");
        } else if (status == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            auto format = AMediaCodec_getOutputFormat(videoCodec);
            LOGI("Format changed");
            AMediaFormat_delete(format);
        }
    }
}