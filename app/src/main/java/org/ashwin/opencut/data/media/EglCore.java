package org.ashwin.opencut.data.media;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
public class EglCore {

    public static final int FLAG_RECORDABLE = 0x01;

    private EGLDisplay eglDisplay =
            EGL14.EGL_NO_DISPLAY;

    private EGLContext eglContext =
            EGL14.EGL_NO_CONTEXT;

    private EGLConfig eglConfig;

    public EglCore() {

        this(null, FLAG_RECORDABLE);
    }

    public EglCore(
            EGLContext sharedContext,
            int flags
    ) {

        if (sharedContext == null) {
            sharedContext =
                    EGL14.EGL_NO_CONTEXT;
        }

        eglDisplay =
                EGL14.eglGetDisplay(
                        EGL14.EGL_DEFAULT_DISPLAY
                );

        if (eglDisplay ==
                EGL14.EGL_NO_DISPLAY) {

            throw new RuntimeException(
                    "Unable to get EGL display"
            );
        }

        int[] version =
                new int[2];

        if (!EGL14.eglInitialize(
                eglDisplay,
                version,
                0,
                version,
                1
        )) {

            throw new RuntimeException(
                    "Unable to initialize EGL"
            );
        }

        EGLConfig config =
                getConfig(flags);

        if (config == null) {

            throw new RuntimeException(
                    "Unable to find EGLConfig"
            );
        }

        int[] attrib3_list = {

                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,

                EGL14.EGL_NONE
        };

        eglContext =
                EGL14.eglCreateContext(
                        eglDisplay,
                        config,
                        sharedContext,
                        attrib3_list,
                        0
                );

        checkEglError(
                "eglCreateContext"
        );

        eglConfig = config;
    }

    private EGLConfig getConfig(
            int flags
    ) {

        int renderableType =
                EGL14.EGL_OPENGL_ES2_BIT;

        int[] attribList = {

                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,

                EGL14.EGL_RENDERABLE_TYPE,
                renderableType,

                EGL14.EGL_NONE,
                0,

                EGL14.EGL_NONE
        };

        if ((flags & FLAG_RECORDABLE) != 0) {

            attribList[attribList.length - 3] =
                    0x3142; // EGL_RECORDABLE_ANDROID

            attribList[attribList.length - 2] =
                    1;
        }

        EGLConfig[] configs =
                new EGLConfig[1];

        int[] numConfigs =
                new int[1];

        if (!EGL14.eglChooseConfig(
                eglDisplay,
                attribList,
                0,
                configs,
                0,
                configs.length,
                numConfigs,
                0
        )) {

            throw new RuntimeException(
                    "eglChooseConfig failed"
            );
        }

        return configs[0];
    }

    public EGLSurface createWindowSurface(
            Object surface
    ) {

        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };

        EGLSurface eglSurface =
                EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        eglConfig,
                        surface,
                        surfaceAttribs,
                        0
                );

        checkEglError(
                "eglCreateWindowSurface"
        );

        return eglSurface;
    }

    public void makeCurrent(
            EGLSurface eglSurface
    ) {

        if (!EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
        )) {

            throw new RuntimeException(
                    "eglMakeCurrent failed"
            );
        }
    }

    public boolean swapBuffers(
            EGLSurface eglSurface
    ) {

        return EGL14.eglSwapBuffers(
                eglDisplay,
                eglSurface
        );
    }

    public void setPresentationTime(
            EGLSurface eglSurface,
            long nsecs
    ) {

        android.opengl.EGLExt
                .eglPresentationTimeANDROID(
                        eglDisplay,
                        eglSurface,
                        nsecs
                );
    }

    public void releaseSurface(
            EGLSurface eglSurface
    ) {

        EGL14.eglDestroySurface(
                eglDisplay,
                eglSurface
        );
    }

    public void release() {

        if (eglDisplay !=
                EGL14.EGL_NO_DISPLAY) {

            EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
            );

            EGL14.eglDestroyContext(
                    eglDisplay,
                    eglContext
            );

            EGL14.eglReleaseThread();

            EGL14.eglTerminate(
                    eglDisplay
            );
        }

        eglDisplay =
                EGL14.EGL_NO_DISPLAY;

        eglContext =
                EGL14.EGL_NO_CONTEXT;

        eglConfig = null;
    }

    private void checkEglError(
            String msg
    ) {

        int error =
                EGL14.eglGetError();

        if (error != EGL14.EGL_SUCCESS) {

            throw new RuntimeException(
                    msg +
                            ": EGL error: 0x" +
                            Integer.toHexString(error)
            );
        }
    }
}