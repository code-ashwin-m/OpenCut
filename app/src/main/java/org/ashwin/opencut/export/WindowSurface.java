package org.ashwin.opencut.export;

import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.view.Surface;

public class WindowSurface {

    private final EglCore eglCore;

    private EGLSurface eglSurface =
            EGL14.EGL_NO_SURFACE;

    private Surface surface;

    public WindowSurface(
            EglCore eglCore,
            Surface surface
    ) {

        this.eglCore = eglCore;
        this.surface = surface;

        eglSurface =
                eglCore.createWindowSurface(
                        surface
                );
    }

    public void makeCurrent() {

        eglCore.makeCurrent(
                eglSurface
        );
    }

    public boolean swapBuffers() {

        return eglCore.swapBuffers(
                eglSurface
        );
    }

    public void setPresentationTime(
            long nsecs
    ) {

        eglCore.setPresentationTime(
                eglSurface,
                nsecs
        );
    }

    public void release() {

        eglCore.releaseSurface(
                eglSurface
        );

        eglSurface =
                EGL14.EGL_NO_SURFACE;

        if (surface != null) {

            surface.release();

            surface = null;
        }
    }

    public EGLSurface getEglSurface() {

        return eglSurface;
    }
}