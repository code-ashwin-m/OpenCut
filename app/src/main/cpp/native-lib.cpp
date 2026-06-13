#include <jni.h>
#include "core/OpenCutEngine.h"
#include "core/VideoRenderer.h"

namespace {
opencut::OpenCutEngine engine;
opencut::VideoRenderer renderer;
opencut::EffectSettings currentEffects;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_ashwin_opencut_platform_nativebridge_NativeEngineBridge_nativeEngineInfo(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF(engine.engineInfo().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_platform_nativebridge_NativeEngineBridge_nativePrepareEngine(
        JNIEnv* /* env */,
        jobject /* this */) {
    engine.prepare();
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_platform_nativebridge_NativeEngineBridge_nativeReleaseEngine(
        JNIEnv* /* env */,
        jobject /* this */) {
    engine.release();
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_platform_nativebridge_NativeEngineBridge_nativeInitRenderer(
        JNIEnv* /* env */,
        jobject /* this */) {
    renderer.init();
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_platform_nativebridge_NativeEngineBridge_nativeSetEffects(
        JNIEnv* /* env */,
        jobject /* this */,
        jfloat brightness,
        jfloat contrast,
        jfloat exposure,
        jfloat highlights,
        jfloat shadows) {
    currentEffects.brightness = brightness;
    currentEffects.contrast = contrast;
    currentEffects.exposure = exposure;
    currentEffects.highlights = highlights;
    currentEffects.shadows = shadows;
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_platform_nativebridge_NativeEngineBridge_nativeDrawFrame(
        JNIEnv* env,
        jobject /* this */,
        jint textureId,
        jfloatArray mvpMatrix) {
    if (mvpMatrix != nullptr) {
        jfloat* mvp = env->GetFloatArrayElements(mvpMatrix, nullptr);
        renderer.draw(textureId, currentEffects, mvp);
        env->ReleaseFloatArrayElements(mvpMatrix, mvp, JNI_ABORT);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_ashwin_opencut_platform_nativebridge_NativeEngineBridge_nativeReleaseRenderer(
        JNIEnv* /* env */,
        jobject /* this */) {
    renderer.release();
}
