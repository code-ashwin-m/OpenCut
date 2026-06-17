#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <aaudio/AAudio.h>
#include <thread>
#include <atomic>
#include <unistd.h>
#include <chrono>
#include <string.h>
#include <sys/types.h>

#define LOG_TAG "NativeVideoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Native state variables
ANativeWindow* nativeWindow = nullptr;
AMediaExtractor* videoExtractor = nullptr;
AMediaExtractor* audioExtractor = nullptr;
AMediaCodec* videoCodec = nullptr;
AMediaCodec* audioCodec = nullptr;
AAudioStream* audioStream = nullptr;

std::thread videoDecodeThread;
std::thread audioDecodeThread;
std::atomic<bool> isPlaying(false);
std::atomic<bool> isThreadRunning(false);

// Decoder state trackers to prevent illegal lifecycle transitions
std::atomic<bool> isVideoCodecStarted(false);
std::atomic<bool> isAudioCodecStarted(false);

// A/V Sync Master Clock State
std::atomic<bool> hasAudio(false);
std::atomic<int64_t> audioMasterClockUs(0);
std::atomic<int64_t> firstAudioPtsUs(-1);
std::atomic<int64_t> resumeFramesRead(0); // Track pre-existing played frames to compute relative timing after a pause
int32_t audioSampleRate = 44100;
int32_t audioChannelCount = 2;

// Forward declarations
void videoDecodeLoop();
void audioDecodeLoop();
void recreateAudioStream(int32_t sampleRate, int32_t channelCount);

// Helper to safely clean up old media resources and stop threads
void cleanupMedia() {
    isPlaying = false;
    isThreadRunning = false;

    // Safely stop and join the decoding threads
    if (videoDecodeThread.joinable()) {
        videoDecodeThread.join();
    }
    if (audioDecodeThread.joinable()) {
        audioDecodeThread.join();
    }

    // Stop and delete the video codec, checking if it was ever started
    if (videoCodec) {
        if (isVideoCodecStarted) {
            AMediaCodec_stop(videoCodec);
            isVideoCodecStarted = false;
        }
        AMediaCodec_delete(videoCodec);
        videoCodec = nullptr;
    }

    // Stop and delete the audio codec, checking if it was ever started
    if (audioCodec) {
        if (isAudioCodecStarted) {
            AMediaCodec_stop(audioCodec);
            isAudioCodecStarted = false;
        }
        AMediaCodec_delete(audioCodec);
        audioCodec = nullptr;
    }

    // Close and destroy the AAudio stream
    if (audioStream) {
        AAudioStream_requestStop(audioStream);
        AAudioStream_close(audioStream);
        audioStream = nullptr;
    }

    // Delete the extractors
    if (videoExtractor) {
        AMediaExtractor_delete(videoExtractor);
        videoExtractor = nullptr;
    }
    if (audioExtractor) {
        AMediaExtractor_delete(audioExtractor);
        audioExtractor = nullptr;
    }

    hasAudio = false;
    firstAudioPtsUs = -1;
    audioMasterClockUs = 0;
    resumeFramesRead = 0;
}

// Helper to safely configure or reconfigure AAudio based on actual decoder formats
void recreateAudioStream(int32_t sampleRate, int32_t channelCount) {
    if (audioStream) {
        AAudioStream_requestStop(audioStream);
        AAudioStream_close(audioStream);
        audioStream = nullptr;
    }

    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    if (AAudioStreamBuilder_openStream(builder, &audioStream) == AAUDIO_OK) {
        AAudioStream_requestStart(audioStream);
        LOGI("AAudio stream successfully configured: SampleRate: %d, Channels: %d", sampleRate, channelCount);
    } else {
        LOGE("Failed to open AAudio stream on configuration change");
    }
    AAudioStreamBuilder_delete(builder);

    // Reset the synchronization timestamp baseline so the master clock adjusts cleanly
    firstAudioPtsUs = -1;
    resumeFramesRead = 0;
}

// JNI bindings mapped to the verified native package structure 'org.ashwin.opencut'
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

    // Duplicate file descriptors so video and audio extractors can scan independently
    int videoFd = dup(fd);
    int audioFd = dup(fd);

    // Reset states
    isVideoCodecStarted = false;
    isAudioCodecStarted = false;

    // Initialize Video Extractor
    videoExtractor = AMediaExtractor_new();
    off64_t videoLength = lseek64(videoFd, 0, SEEK_END);
    lseek64(videoFd, 0, SEEK_SET);
    if (AMediaExtractor_setDataSourceFd(videoExtractor, videoFd, 0, videoLength) != AMEDIA_OK) {
        LOGE("Failed to set video extractor data source");
        close(videoFd);
        close(audioFd);
        return;
    }

    // Initialize Audio Extractor
    audioExtractor = AMediaExtractor_new();
    off64_t audioLength = lseek64(audioFd, 0, SEEK_END);
    lseek64(audioFd, 0, SEEK_SET);
    if (AMediaExtractor_setDataSourceFd(audioExtractor, audioFd, 0, audioLength) != AMEDIA_OK) {
        LOGE("Failed to set audio extractor data source");
        close(videoFd);
        close(audioFd);
        return;
    }

    close(videoFd);
    close(audioFd);

    // 1. Configure Video Pipeline
    int numTracks = AMediaExtractor_getTrackCount(videoExtractor);
    for (int i = 0; i < numTracks; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(videoExtractor, i);
        const char* mime;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && strncmp(mime, "video/", 6) == 0) {
            AMediaExtractor_selectTrack(videoExtractor, i);
            videoCodec = AMediaCodec_createDecoderByType(mime);
            AMediaCodec_configure(videoCodec, format, nativeWindow, nullptr, 0);
            AMediaFormat_delete(format);
            LOGI("Video Pipeline configured.");
            break;
        }
        AMediaFormat_delete(format);
    }

    // 2. Configure Audio Pipeline (Pre-configure Decoder, delay AAudio initialization until runtime format confirmation)
    numTracks = AMediaExtractor_getTrackCount(audioExtractor);
    for (int i = 0; i < numTracks; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(audioExtractor, i);
        const char* mime;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && strncmp(mime, "audio/", 6) == 0) {
            AMediaExtractor_selectTrack(audioExtractor, i);

            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &audioSampleRate);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &audioChannelCount);

            audioCodec = AMediaCodec_createDecoderByType(mime);
            AMediaCodec_configure(audioCodec, format, nullptr, nullptr, 0);
            AMediaFormat_delete(format);
            hasAudio = true;
            break;
        }
        AMediaFormat_delete(format);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_play(JNIEnv*, jobject) {
    if (!isPlaying) {
        isPlaying = true;
        isThreadRunning = true;

        // FIXED: Only start hardware codecs if they are not already in an executing state
        if (videoCodec && !isVideoCodecStarted) {
            AMediaCodec_start(videoCodec);
            isVideoCodecStarted = true;
        }
        if (audioCodec && !isAudioCodecStarted) {
            AMediaCodec_start(audioCodec);
            isAudioCodecStarted = true;
        }

        // FIXED: Explicitly resume the physical AAudio stream after a pause
        if (audioStream) {
            AAudioStream_requestStart(audioStream);
        }

        videoDecodeThread = std::thread(videoDecodeLoop);
        if (hasAudio) {
            audioDecodeThread = std::thread(audioDecodeLoop);
        }
        LOGI("Engine Playback Started");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_pause(JNIEnv*, jobject) {
    isPlaying = false;
    isThreadRunning = false;

    if (videoDecodeThread.joinable()) videoDecodeThread.join();
    if (audioDecodeThread.joinable()) audioDecodeThread.join();

    if (audioStream) AAudioStream_requestPause(audioStream);

    // Only flush codecs if they are currently active/executing
    if (videoCodec && isVideoCodecStarted) {
        AMediaCodec_flush(videoCodec);
    }
    if (audioCodec && isAudioCodecStarted) {
        AMediaCodec_flush(audioCodec);
    }

    // FIXED: Clear sync values on pause so the clock is safely re-calibrated upon play
    firstAudioPtsUs = -1;
    audioMasterClockUs = 0;

    LOGI("Engine Playback Paused");
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_release(JNIEnv*, jobject) {
    cleanupMedia();
}

// ---------------------------------------------------------
// Native Audio Decoding & Playback Loop
// ---------------------------------------------------------
void audioDecodeLoop() {
    if (!audioCodec || !audioExtractor) {
        LOGE("Audio components missing during initialization, aborting audio thread.");
        return;
    }

    bool isEOS = false;

    while (isThreadRunning) {
        if (!isEOS) {
            ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(audioCodec, 5000);
            if (bufIdx >= 0) {
                size_t bufSize;
                uint8_t* buf = AMediaCodec_getInputBuffer(audioCodec, bufIdx, &bufSize);
                ssize_t sampleSize = AMediaExtractor_readSampleData(audioExtractor, buf, bufSize);

                if (sampleSize < 0) {
                    AMediaCodec_queueInputBuffer(audioCodec, bufIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    isEOS = true;
                } else {
                    int64_t pts = AMediaExtractor_getSampleTime(audioExtractor);
                    AMediaCodec_queueInputBuffer(audioCodec, bufIdx, 0, sampleSize, pts, 0);
                    AMediaExtractor_advance(audioExtractor);
                }
            }
        }

        AMediaCodecBufferInfo info;
        ssize_t status = AMediaCodec_dequeueOutputBuffer(audioCodec, &info, 5000);

        if (status >= 0) {
            // FIXED: Capture the exact hardware frames played prior to this playback segment
            if (firstAudioPtsUs == -1 && info.presentationTimeUs >= 0) {
                firstAudioPtsUs = info.presentationTimeUs;
                if (audioStream) {
                    resumeFramesRead.store(AAudioStream_getFramesRead(audioStream));
                } else {
                    resumeFramesRead.store(0);
                }
            }

            size_t outSize;
            uint8_t* pcmBuf = AMediaCodec_getOutputBuffer(audioCodec, status, &outSize);

            // Only play if the AAudio hardware stream is initialized and ready
            if (pcmBuf && outSize > 0 && audioStream) {
                int32_t numFrames = outSize / (sizeof(int16_t) * audioChannelCount);

                // Write PCM buffer directly to AAudio
                AAudioStream_write(audioStream, pcmBuf, numFrames, 100000000); // 100ms timeout

                // FIXED: Keep synchronization tracking perfectly calibrated relative to resume offsets
                int64_t framesRead = AAudioStream_getFramesRead(audioStream);
                int64_t relativeFrames = framesRead - resumeFramesRead.load();
                if (relativeFrames < 0) relativeFrames = 0;

                if (firstAudioPtsUs != -1) {
                    int64_t currentPts = firstAudioPtsUs + (relativeFrames * 1000000LL / audioSampleRate);
                    audioMasterClockUs.store(currentPts);
                }
            }
            AMediaCodec_releaseOutputBuffer(audioCodec, status, false);
        } else if (status == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* format = AMediaCodec_getOutputFormat(audioCodec);
            int32_t actualSampleRate = audioSampleRate;
            int32_t actualChannelCount = audioChannelCount;

            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &actualSampleRate);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &actualChannelCount);
            AMediaFormat_delete(format);

            LOGI("Audio Decoder format confirmed: Rate: %d, Channels: %d", actualSampleRate, actualChannelCount);

            if (actualSampleRate != audioSampleRate || actualChannelCount != audioChannelCount || !audioStream) {
                audioSampleRate = actualSampleRate;
                audioChannelCount = actualChannelCount;
                recreateAudioStream(audioSampleRate, audioChannelCount);
            }
        }
    }
}

// ---------------------------------------------------------
// Native Video Decoding & Sync-To-Audio Loop
// ---------------------------------------------------------
void videoDecodeLoop() {
    if (!videoCodec || !videoExtractor) {
        LOGE("Video components missing during initialization, aborting video thread.");
        return;
    }

    bool isEOS = false;

    auto systemStartTime = std::chrono::steady_clock::now();
    int64_t firstVideoPts = -1;
    bool isFirstFrame = true;

    while (isThreadRunning) {
        if (!isEOS) {
            ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(videoCodec, 5000);
            if (bufIdx >= 0) {
                size_t bufSize;
                uint8_t* buf = AMediaCodec_getInputBuffer(videoCodec, bufIdx, &bufSize);
                ssize_t sampleSize = AMediaExtractor_readSampleData(videoExtractor, buf, bufSize);

                if (sampleSize < 0) {
                    AMediaCodec_queueInputBuffer(videoCodec, bufIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    isEOS = true;
                } else {
                    int64_t pts = AMediaExtractor_getSampleTime(videoExtractor);
                    AMediaCodec_queueInputBuffer(videoCodec, bufIdx, 0, sampleSize, pts, 0);
                    AMediaExtractor_advance(videoExtractor);
                }
            }
        }

        AMediaCodecBufferInfo info;
        ssize_t status = AMediaCodec_dequeueOutputBuffer(videoCodec, &info, 5000);
        if (status >= 0) {
            int64_t framePts = info.presentationTimeUs;

            if (isFirstFrame) {
                firstVideoPts = framePts;
                systemStartTime = std::chrono::steady_clock::now();
                isFirstFrame = false;
            }

            // Sync Core Control
            if (hasAudio && audioMasterClockUs.load() > 0) {
                // 1. Sync directly to Audio Master Clock
                int64_t masterClock = audioMasterClockUs.load();

                if (framePts > masterClock) {
                    int64_t sleepTimeUs = framePts - masterClock;
                    if (sleepTimeUs < 1000000) {
                        std::this_thread::sleep_for(std::chrono::microseconds(sleepTimeUs));
                    }
                }
            } else {
                // 2. High-precision system clock fallback (if video is silent or clock is starting)
                auto now = std::chrono::steady_clock::now();
                int64_t elapsedRealTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(now - systemStartTime).count();
                int64_t targetElapsedUs = framePts - firstVideoPts;

                if (targetElapsedUs > elapsedRealTimeUs) {
                    int64_t sleepTimeUs = targetElapsedUs - elapsedRealTimeUs;
                    if (sleepTimeUs < 1000000) {
                        std::this_thread::sleep_for(std::chrono::microseconds(sleepTimeUs));
                    }
                }
            }

            // Release frame to Surface for rendering
            AMediaCodec_releaseOutputBuffer(videoCodec, status, true);

            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                isThreadRunning = false;
            }
        }
    }
}