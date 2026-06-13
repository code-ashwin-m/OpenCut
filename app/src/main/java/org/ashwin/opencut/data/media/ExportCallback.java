package org.ashwin.opencut.data.media;

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
