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
#include <string>
#include <vector>
#include <sys/types.h>

#define LOG_TAG "NativeVideoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


// =========================================================
// CROSS-PLATFORM DOMAIN MODELS
// These structs will compile cleanly in Xcode for iOS as well.
// =========================================================

struct TimelineClip {
    std::string id;
    std::string assetUri;
    std::string name;
    int64_t durationMs;
};

struct Project {
    std::string id;
    std::string name;
    int64_t createdAt;
    std::string aspectRatio;
    std::string videoUri;
    std::vector<std::string> assets;
    std::vector<TimelineClip> timelineClips;
};


// =========================================================
// CORE VIDEO ENGINE CLASS
// Wraps Android NDK implementations & Core Project state
// =========================================================

class VideoEditorEngine {
public:
    // Core Project Data
    Project activeProject;

    // Hardware Render / Decode states
    ANativeWindow* nativeWindow = nullptr;
    AMediaExtractor* videoExtractor = nullptr;
    AMediaExtractor* audioExtractor = nullptr;
    AMediaCodec* videoCodec = nullptr;
    AMediaCodec* audioCodec = nullptr;
    AAudioStream* audioStream = nullptr;

    std::thread videoDecodeThread;
    std::thread audioDecodeThread;

    std::atomic<bool> isPlaying{false};
    std::atomic<bool> isThreadRunning{false};

    // Lifecycle transition trackers
    std::atomic<bool> isVideoCodecStarted{false};
    std::atomic<bool> isAudioCodecStarted{false};

    // A/V Sync Master Clock State
    std::atomic<bool> hasAudio{false};
    std::atomic<int64_t> audioMasterClockUs{0};
    std::atomic<int64_t> firstAudioPtsUs{-1};
    std::atomic<int64_t> resumeFramesRead{0};
    std::atomic<int64_t> currentPlaybackPositionUs{0};

    int32_t audioSampleRate = 44100;
    int32_t audioChannelCount = 2;

    VideoEditorEngine() {}

    ~VideoEditorEngine() {
        cleanupMedia();
        if (nativeWindow) {
            ANativeWindow_release(nativeWindow);
        }
    }

    void cleanupMedia() {
        isPlaying = false;
        isThreadRunning = false;

        if (videoDecodeThread.joinable()) {
            videoDecodeThread.join();
        }
        if (audioDecodeThread.joinable()) {
            audioDecodeThread.join();
        }

        if (videoCodec) {
            if (isVideoCodecStarted) {
                AMediaCodec_stop(videoCodec);
                isVideoCodecStarted = false;
            }
            AMediaCodec_delete(videoCodec);
            videoCodec = nullptr;
        }

        if (audioCodec) {
            if (isAudioCodecStarted) {
                AMediaCodec_stop(audioCodec);
                isAudioCodecStarted = false;
            }
            AMediaCodec_delete(audioCodec);
            audioCodec = nullptr;
        }

        if (audioStream) {
            AAudioStream_requestStop(audioStream);
            AAudioStream_close(audioStream);
            audioStream = nullptr;
        }

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
        currentPlaybackPositionUs = 0;
    }

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

        firstAudioPtsUs = -1;
        resumeFramesRead = 0;
    }

    void videoDecodeLoop() {
        if (!videoCodec || !videoExtractor) return;
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

                if (hasAudio && audioMasterClockUs.load() > 0) {
                    int64_t masterClock = audioMasterClockUs.load();
                    if (framePts > masterClock) {
                        int64_t sleepTimeUs = framePts - masterClock;
                        if (sleepTimeUs < 1000000) {
                            std::this_thread::sleep_for(std::chrono::microseconds(sleepTimeUs));
                        }
                    }
                } else {
                    auto now = std::chrono::steady_clock::now();
                    int64_t elapsedRealTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(now - systemStartTime).count();
                    int64_t targetElapsedUs = framePts - firstVideoPts;

                    if (targetElapsedUs > elapsedRealTimeUs) {
                        int64_t sleepTimeUs = targetElapsedUs - elapsedRealTimeUs;
                        if (sleepTimeUs < 1000000) {
                            std::this_thread::sleep_for(std::chrono::microseconds(sleepTimeUs));
                        }
                    }
                    currentPlaybackPositionUs.store(framePts);
                }

                AMediaCodec_releaseOutputBuffer(videoCodec, status, true);
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    isThreadRunning = false;
                }
            }
        }
    }

    void audioDecodeLoop() {
        if (!audioCodec || !audioExtractor) return;
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

                if (pcmBuf && outSize > 0 && audioStream) {
                    int32_t numFrames = outSize / (sizeof(int16_t) * audioChannelCount);
                    AAudioStream_write(audioStream, pcmBuf, numFrames, 100000000);

                    int64_t framesRead = AAudioStream_getFramesRead(audioStream);
                    int64_t relativeFrames = framesRead - resumeFramesRead.load();
                    if (relativeFrames < 0) relativeFrames = 0;

                    if (firstAudioPtsUs != -1) {
                        int64_t currentPts = firstAudioPtsUs + (relativeFrames * 1000000LL / audioSampleRate);
                        audioMasterClockUs.store(currentPts);
                        currentPlaybackPositionUs.store(currentPts);
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

                if (actualSampleRate != audioSampleRate || actualChannelCount != audioChannelCount || !audioStream) {
                    audioSampleRate = actualSampleRate;
                    audioChannelCount = actualChannelCount;
                    recreateAudioStream(audioSampleRate, audioChannelCount);
                }
            }
        }
    }
};

// =========================================================
// JNI BINDINGS
// =========================================================

// Global Singleton Engine instance (per editor session)
VideoEditorEngine* g_engine = new VideoEditorEngine();

// JNI String Conversion helper
std::string jstringToString(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";
    const char* chars = env->GetStringUTFChars(jStr, NULL);
    std::string ret(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return ret;
}

// --- PROJECT STATE SYNC ---

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_initProjectState(JNIEnv* env, jobject, jstring id, jstring name, jlong createdAt, jstring ratio) {
    if (g_engine) {
        g_engine->activeProject.id = jstringToString(env, id);
        g_engine->activeProject.name = jstringToString(env, name);
        g_engine->activeProject.createdAt = createdAt;
        g_engine->activeProject.aspectRatio = jstringToString(env, ratio);
        g_engine->activeProject.assets.clear();
        g_engine->activeProject.timelineClips.clear();
        LOGI("Native Core: Project %s loaded in C++", g_engine->activeProject.name.c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_addAssetToProject(JNIEnv* env, jobject, jstring assetUri) {
    if (g_engine) {
        g_engine->activeProject.assets.push_back(jstringToString(env, assetUri));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_addTimelineClip(JNIEnv* env, jobject, jstring id, jstring uri, jstring name, jlong durationMs) {
    if (g_engine) {
        TimelineClip clip;
        clip.id = jstringToString(env, id);
        clip.assetUri = jstringToString(env, uri);
        clip.name = jstringToString(env, name);
        clip.durationMs = durationMs;
        g_engine->activeProject.timelineClips.push_back(clip);
    }
}

// --- MEDIA HARDWARE SYNC ---

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_setSurface(JNIEnv* env, jobject, jobject surface) {
    if (g_engine->nativeWindow) {
        ANativeWindow_release(g_engine->nativeWindow);
    }
    g_engine->nativeWindow = ANativeWindow_fromSurface(env, surface);
    LOGI("Native Surface set.");
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_releaseSurface(JNIEnv*, jobject) {
    if (g_engine->nativeWindow) {
        ANativeWindow_release(g_engine->nativeWindow);
        g_engine->nativeWindow = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_setDataSource(JNIEnv* env, jobject, jint fd) {
    g_engine->cleanupMedia();

    int videoFd = dup(fd);
    int audioFd = dup(fd);

    g_engine->isVideoCodecStarted = false;
    g_engine->isAudioCodecStarted = false;

    g_engine->videoExtractor = AMediaExtractor_new();
    off64_t videoLength = lseek64(videoFd, 0, SEEK_END);
    lseek64(videoFd, 0, SEEK_SET);
    if (AMediaExtractor_setDataSourceFd(g_engine->videoExtractor, videoFd, 0, videoLength) != AMEDIA_OK) {
        LOGE("Failed to set video extractor data source");
        close(videoFd); close(audioFd); return;
    }

    g_engine->audioExtractor = AMediaExtractor_new();
    off64_t audioLength = lseek64(audioFd, 0, SEEK_END);
    lseek64(audioFd, 0, SEEK_SET);
    if (AMediaExtractor_setDataSourceFd(g_engine->audioExtractor, audioFd, 0, audioLength) != AMEDIA_OK) {
        LOGE("Failed to set audio extractor data source");
        close(videoFd); close(audioFd); return;
    }

    close(videoFd); close(audioFd);

    int numTracks = AMediaExtractor_getTrackCount(g_engine->videoExtractor);
    for (int i = 0; i < numTracks; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(g_engine->videoExtractor, i);
        const char* mime;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && strncmp(mime, "video/", 6) == 0) {
            AMediaExtractor_selectTrack(g_engine->videoExtractor, i);
            g_engine->videoCodec = AMediaCodec_createDecoderByType(mime);
            AMediaCodec_configure(g_engine->videoCodec, format, g_engine->nativeWindow, nullptr, 0);
            AMediaFormat_delete(format);
            LOGI("Video Pipeline configured.");
            break;
        }
        AMediaFormat_delete(format);
    }

    numTracks = AMediaExtractor_getTrackCount(g_engine->audioExtractor);
    for (int i = 0; i < numTracks; i++) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(g_engine->audioExtractor, i);
        const char* mime;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && strncmp(mime, "audio/", 6) == 0) {
            AMediaExtractor_selectTrack(g_engine->audioExtractor, i);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &g_engine->audioSampleRate);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &g_engine->audioChannelCount);
            g_engine->audioCodec = AMediaCodec_createDecoderByType(mime);
            AMediaCodec_configure(g_engine->audioCodec, format, nullptr, nullptr, 0);
            AMediaFormat_delete(format);
            g_engine->hasAudio = true;
            break;
        }
        AMediaFormat_delete(format);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_play(JNIEnv*, jobject) {
    if (!g_engine->isPlaying) {
        g_engine->isPlaying = true;
        g_engine->isThreadRunning = true;

        if (g_engine->videoCodec && !g_engine->isVideoCodecStarted) {
            AMediaCodec_start(g_engine->videoCodec);
            g_engine->isVideoCodecStarted = true;
        }
        if (g_engine->audioCodec && !g_engine->isAudioCodecStarted) {
            AMediaCodec_start(g_engine->audioCodec);
            g_engine->isAudioCodecStarted = true;
        }

        if (g_engine->audioStream) {
            AAudioStream_requestStart(g_engine->audioStream);
        }

        // Pass 'this' equivalent (g_engine) to the thread so it scopes correctly within the class!
        g_engine->videoDecodeThread = std::thread(&VideoEditorEngine::videoDecodeLoop, g_engine);
        if (g_engine->hasAudio) {
            g_engine->audioDecodeThread = std::thread(&VideoEditorEngine::audioDecodeLoop, g_engine);
        }
        LOGI("Engine Playback Started");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_pause(JNIEnv*, jobject) {
    g_engine->isPlaying = false;
    g_engine->isThreadRunning = false;

    if (g_engine->videoDecodeThread.joinable()) g_engine->videoDecodeThread.join();
    if (g_engine->audioDecodeThread.joinable()) g_engine->audioDecodeThread.join();

    if (g_engine->audioStream) AAudioStream_requestPause(g_engine->audioStream);

    if (g_engine->videoCodec && g_engine->isVideoCodecStarted) {
        AMediaCodec_flush(g_engine->videoCodec);
    }
    if (g_engine->audioCodec && g_engine->isAudioCodecStarted) {
        AMediaCodec_flush(g_engine->audioCodec);
    }

    g_engine->firstAudioPtsUs = -1;
    g_engine->audioMasterClockUs = 0;

    LOGI("Engine Playback Paused");
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_NativeEngine_release(JNIEnv*, jobject) {
    g_engine->cleanupMedia();
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_ashwin_opencut_NativeEngine_getCurrentPositionUs(JNIEnv*, jobject) {
    return g_engine->currentPlaybackPositionUs.load();
}