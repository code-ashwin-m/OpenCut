package org.ashwin.opencut.export;

import android.util.Log;
import android.view.Surface;

public class InputSurface {

    private final EglCore eglCore;

    private final WindowSurface windowSurface;

    public InputSurface(
            EglCore eglCore,
            Surface encoderSurface
    ) {
        this.eglCore = eglCore;

        windowSurface =
                new WindowSurface(
                        eglCore,
                        encoderSurface
                );

        windowSurface.makeCurrent();
    }

    public void makeCurrent() {

        windowSurface.makeCurrent();
    }

    public void swapBuffers() {
        Log.d(
                "EXPORT",
                "swapBuffers"
        );

        windowSurface.swapBuffers();
    }

    public void setPresentationTime(
            long presentationTimeNs
    ) {

        windowSurface.setPresentationTime(
                presentationTimeNs
        );
    }

    public void release() {

        windowSurface.release();

        eglCore.release();
    }
}