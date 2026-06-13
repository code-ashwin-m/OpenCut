#include <jni.h>

#include "core/OpenCutEngine.h"

namespace {
opencut::OpenCutEngine engine;
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
