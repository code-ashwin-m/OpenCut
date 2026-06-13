package org.ashwin.opencut.export;

public interface ExportCallback {
    void onProgress(
            float progress
    );

    void onSuccess(
            String outputPath
    );

    void onError(
            Exception exception
    );
}
