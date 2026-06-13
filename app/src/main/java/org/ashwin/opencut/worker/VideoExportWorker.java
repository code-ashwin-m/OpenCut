package org.ashwin.opencut.worker;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.ashwin.opencut.data.media.ExportCallback;
import org.ashwin.opencut.data.media.VideoExporter;
import org.ashwin.opencut.domain.model.EffectSettings;

import java.util.concurrent.CountDownLatch;

public class VideoExportWorker extends Worker {

    public static final String KEY_INPUT_URI = "input_uri";
    public static final String KEY_OUTPUT_PATH = "output_path";
    public static final String KEY_BRIGHTNESS = "brightness";
    public static final String KEY_PROGRESS = "progress";

    public VideoExportWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String inputUriStr = getInputData().getString(KEY_INPUT_URI);
        String outputPath = getInputData().getString(KEY_OUTPUT_PATH);
        float brightness = getInputData().getFloat(KEY_BRIGHTNESS, 0f);

        if (inputUriStr == null || outputPath == null) {
            return Result.failure();
        }

        Uri inputUri = Uri.parse(inputUriStr);
        EffectSettings settings = new EffectSettings();
        settings.brightness = brightness;

        VideoExporter exporter = new VideoExporter(getApplicationContext());
        
        final boolean[] success = {false};
        final boolean[] error = {false};
        CountDownLatch latch = new CountDownLatch(1);

        exporter.exportSync(inputUri, outputPath, settings, new ExportCallback() {
            @Override
            public void onProgress(float progress) {
                setProgressAsync(new Data.Builder()
                        .putFloat(KEY_PROGRESS, progress)
                        .build());
            }

            @Override
            public void onSuccess(String outputPath) {
                success[0] = true;
                setProgressAsync(new Data.Builder().putFloat(KEY_PROGRESS, 1.0f).build());
                latch.countDown();
            }

            @Override
            public void onError(Exception exception) {
                Log.e("VideoExportWorker", "Export failed", exception);
                error[0] = true;
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            return Result.failure();
        }

        if (success[0]) {
            return Result.success();
        } else {
            return Result.failure();
        }
    }
}
