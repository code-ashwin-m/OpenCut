//
// Created by Ashwin M on 15/06/26.
//

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <thread>
#include <mutex>
#include <atomic>
#include <string>

#include "resolver/TimelineResolver.h"
#include "adjustment/VideoShader.h"
#include "json.hpp"

#define LOG_TAG "EditorEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

JavaVM* g_JavaVM = nullptr;

class VideoRenderer {
public:
    VideoRenderer() :
            mNativeWindow(nullptr),
            mEglDisplay(EGL_NO_DISPLAY),
            mEglContext(EGL_NO_CONTEXT),
            mEglSurface(EGL_NO_SURFACE),
            mOesTextureId(0),
            mIsPlaying(false),
            mCurrentTimeMs(0),
            mRenderThreadActive(false),
            mSurfaceChanged(false),
            mViewportWidth(0),
            mViewportHeight(0) {}

    ~VideoRenderer() {
        StopRenderThread();
        ReleaseEGL();
    }

    void SetSurface(ANativeWindow* window) {
        std::lock_guard<std::mutex> lock(mContextMutex);
        if (mNativeWindow != nullptr) {
            ANativeWindow_release(mNativeWindow);
        }
        mNativeWindow = window;
        mSurfaceChanged = true;
    }

    void UpdateComposition(const std::vector<NativeTrack>& tracks) {
        mTimelineResolver.SetComposition(tracks);
        RequestFrameRender();
    }

    void SeekTo(long positionMs) {
        mCurrentTimeMs = positionMs;
        RequestFrameRender();
    }

    void SetPlaying(bool playing) {
        mIsPlaying = playing;
        if (playing) {
            StartRenderThread();
        } else {
            StopRenderThread();
        }
    }

private:
    void StartRenderThread() {
        if (mRenderThreadActive) return;
        mRenderThreadActive = true;
        mRenderThread = std::thread(&VideoRenderer::RenderLoop, this);
    }

    void StopRenderThread() {
        mRenderThreadActive = false;
        if (mRenderThread.joinable()) {
            mRenderThread.join();
        }
    }

    void InitEGL() {
        mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglInitialize(mEglDisplay, nullptr, nullptr);

        const EGLint configAttribs[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_BLUE_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_RED_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_DEPTH_SIZE, 16,
                EGL_NONE
        };

        EGLConfig config;
        EGLint numConfigs;
        eglChooseConfig(mEglDisplay, configAttribs, &config, 1, &numConfigs);

        const EGLint contextAttribs[] = {
                EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL_NONE
        };

        mEglContext = eglCreateContext(mEglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
        mEglSurface = eglCreateWindowSurface(mEglDisplay, config, mNativeWindow, nullptr);

        if (eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext) == EGL_FALSE) {
            LOGE("Failed to make EGL context current.");
        }

        // Query Surface dimensions for dynamic viewport calculations
        eglQuerySurface(mEglDisplay, mEglSurface, EGL_WIDTH, &mViewportWidth);
        eglQuerySurface(mEglDisplay, mEglSurface, EGL_HEIGHT, &mViewportHeight);

        // Initialize shader pipelines and textures inside this active thread
        mShader.Initialize();

        glGenTextures(1, &mOesTextureId);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, mOesTextureId);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        LOGI("EGL and OpenGL ES resources configured. TextureId: %u", mOesTextureId);
    }

    void ReleaseEGL() {
        if (mEglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (mOesTextureId != 0) {
                glDeleteTextures(1, &mOesTextureId);
                mOesTextureId = 0;
            }
            if (mEglSurface != EGL_NO_SURFACE) {
                eglDestroySurface(mEglDisplay, mEglSurface);
                mEglSurface = EGL_NO_SURFACE;
            }
            if (mEglContext != EGL_NO_CONTEXT) {
                eglDestroyContext(mEglDisplay, mEglContext);
                mEglContext = EGL_NO_CONTEXT;
            }
            eglTerminate(mEglDisplay);
            mEglDisplay = EGL_NO_DISPLAY;
        }
    }

    void RenderLoop() {
        JNIEnv* env = nullptr;
        bool isAttached = false;

        // Attach the current C++ thread to the JavaVM to enable system callback JNI Env lookups
        if (g_JavaVM->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_JavaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                isAttached = true;
                LOGI("Rendering thread successfully attached to JavaVM.");
            } else {
                LOGE("Failed to attach rendering thread to JavaVM.");
            }
        }

        while (mRenderThreadActive) {
            std::lock_guard<std::mutex> lock(mContextMutex);

            if (mNativeWindow == nullptr) {
                std::this_thread::sleep_for(std::chrono::milliseconds(16));
                continue;
            }

            if (mSurfaceChanged) {
                ReleaseEGL();
                InitEGL();
                mSurfaceChanged = false;
            }

            // 1. Calculate Frame Timing
            // 2. Decode nearest video frame via NdkMediaCodec
            // 3. Upload decoded frames into OES Textures
            // 4. Bind Custom GLSL Shader (Transformations, Adjustments, Filters)

            glViewport(0, 0, mViewportWidth, mViewportHeight);
            glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // [Graphics Pass] Draw video layer, render text overlays here

            NativeClip currentClip;
            bool frameResolved = mTimelineResolver.ResolveFrameAtTime(mCurrentTimeMs, mOesTextureId, currentClip);

            if (frameResolved) {
                // Execute GPU draw pass using the clip's dynamic parameters
                mShader.DrawFrame(
                        mOesTextureId,
                        currentClip.scaleX, currentClip.scaleY,
                        currentClip.rotation,
                        currentClip.translationX, currentClip.translationY,
                        currentClip.brightness, currentClip.contrast, currentClip.saturation,
                        mViewportWidth, mViewportHeight
                );
            }

            eglSwapBuffers(mEglDisplay, mEglSurface);

            if (mIsPlaying) {
                mCurrentTimeMs += 16; // Increment roughly 60fps frame delta
                std::this_thread::sleep_for(std::chrono::milliseconds(16));
            } else {
                mRenderThreadActive = false; // Turn off thread if paused
            }
        }

        // Detach thread right before thread exit
        if (isAttached) {
            g_JavaVM->DetachCurrentThread();
            LOGI("Rendering thread safely detached from JavaVM.");
        }
    }

    void RequestFrameRender() {
        if (!mIsPlaying) {
            StartRenderThread(); // Run exactly one iteration to update preview
        }
    }

    ANativeWindow* mNativeWindow;
    EGLDisplay mEglDisplay;
    EGLContext mEglContext;
    EGLSurface mEglSurface;
    GLuint mOesTextureId;

    std::atomic<bool> mIsPlaying;
    std::atomic<long> mCurrentTimeMs;
    std::atomic<bool> mRenderThreadActive;
    bool mSurfaceChanged = false;

    int mViewportWidth;
    int mViewportHeight;

    std::thread mRenderThread;
    std::mutex mContextMutex;
    std::mutex mCompositionMutex;

    TimelineResolver mTimelineResolver;
    VideoShader mShader;
};

// --- JNI Bridge Bindings ---

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_JavaVM = vm; // Cache the global Java Virtual Machine pointer
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_org_ashwin_opencut_engine_render_VideoRenderBridge_nativeInit(JNIEnv *env, jobject thiz) {
    auto* renderer = new VideoRenderer();
    return reinterpret_cast<jlong>(renderer);
}

JNIEXPORT void JNICALL
Java_org_ashwin_opencut_engine_render_VideoRenderBridge_nativeRelease(JNIEnv *env, jobject thiz, jlong handle) {
    auto* renderer = reinterpret_cast<VideoRenderer*>(handle);
    delete renderer;
}

JNIEXPORT void JNICALL
Java_org_ashwin_opencut_engine_render_VideoRenderBridge_nativeSetSurface(JNIEnv *env, jobject thiz, jlong handle, jobject surface) {
    auto* renderer = reinterpret_cast<VideoRenderer*>(handle);
    if (surface != nullptr) {
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        renderer->SetSurface(window);
    } else {
        renderer->SetSurface(nullptr);
    }
}

JNIEXPORT void JNICALL
Java_org_ashwin_opencut_engine_render_VideoRenderBridge_nativeUpdateComposition(JNIEnv *env, jobject thiz, jlong handle, jstring composition_json) {
    auto* renderer = reinterpret_cast<VideoRenderer*>(handle);
    const char* jsonStr = env->GetStringUTFChars(composition_json, nullptr);

    try {
        // REAL CODE: Parse the incoming composition JSON dynamically
        auto compJson = json::parse(jsonStr);
        std::vector<NativeTrack> resolvedTracks;

        if (compJson.contains("tracks") && compJson["tracks"].is_array()) {
            for (const auto& trackItem : compJson["tracks"]) {
                NativeTrack track;
                track.id = trackItem.value("id", "");

                std::string typeStr = trackItem.value("type", "VIDEO");
                track.type = (typeStr == "VIDEO") ? 0 : 1; // 0=Video, 1=Audio

                if (trackItem.contains("clips") && trackItem["clips"].is_array()) {
                    for (const auto& clipItem : trackItem["clips"]) {
                        NativeClip clip;
                        clip.id = clipItem.value("id", "");
                        clip.filePath = clipItem.value("filePath", "");
                        clip.sourceInMs = clipItem.value("sourceInMs", 0LL);
                        clip.sourceOutMs = clipItem.value("sourceOutMs", 0LL);
                        clip.timelineStartMs = clipItem.value("timelineStartMs", 0LL);
                        clip.timelineEndMs = clipItem.value("timelineEndMs", 0LL);
                        clip.speed = clipItem.value("speed", 1.0f);

                        // Extract Transform Struct
                        if (clipItem.contains("transform") && clipItem["transform"].is_object()) {
                            auto t = clipItem["transform"];
                            clip.scaleX = t.value("scaleX", 1.0f);
                            clip.scaleY = t.value("scaleY", 1.0f);
                            clip.rotation = t.value("rotation", 0.0f);
                            clip.translationX = t.value("translationX", 0.0f);
                            clip.translationY = t.value("translationY", 0.0f);
                        }

                        // Extract Color Adjustments Struct
                        if (clipItem.contains("adjustments") && clipItem["adjustments"].is_object()) {
                            auto a = clipItem["adjustments"];
                            clip.brightness = a.value("brightness", 0.0f);
                            clip.contrast = a.value("contrast", 1.0f);
                            clip.saturation = a.value("saturation", 1.0f);
                        }

                        track.clips.push_back(clip);
                    }
                }
                resolvedTracks.push_back(track);
            }
        }

        // Apply properties to the central renderer loop context
        renderer->UpdateComposition(resolvedTracks);

    } catch (const std::exception& e) {
        LOGE("Failed to parse native Composition state JSON: %s", e.what());
    }

    env->ReleaseStringUTFChars(composition_json, jsonStr);
}

JNIEXPORT void JNICALL
Java_org_ashwin_opencut_engine_render_VideoRenderBridge_nativeSeekTo(JNIEnv *env, jobject thiz, jlong handle, jlong position_ms) {
    auto* renderer = reinterpret_cast<VideoRenderer*>(handle);
    renderer->SeekTo(position_ms);
}

JNIEXPORT void JNICALL
Java_org_ashwin_opencut_engine_render_VideoRenderBridge_nativeSetPlaying(JNIEnv *env, jobject thiz, jlong handle, jboolean is_playing) {
    auto* renderer = reinterpret_cast<VideoRenderer*>(handle);
    renderer->SetPlaying(is_playing);
}

}