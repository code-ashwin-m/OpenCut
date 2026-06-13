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

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

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
            new File(outputPath).delete();
            return Result.failure();
        }

        if (success[0]) {
            boolean copied = copyToMediaStore(outputPath);
            new File(outputPath).delete();
            if (copied) {
                return Result.success();
            } else {
                return Result.failure();
            }
        } else {
            new File(outputPath).delete();
            return Result.failure();
        }
    }

    private boolean copyToMediaStore(String tempPath) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "opencut_export_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OpenCut");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        ContentResolver resolver = getApplicationContext().getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        Uri itemUri = resolver.insert(collection, values);
        if (itemUri == null) return false;

        try (OutputStream os = resolver.openOutputStream(itemUri);
             FileInputStream is = new FileInputStream(tempPath)) {

            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(itemUri, values, null, null);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            resolver.delete(itemUri, null, null);
            return false;
        }
    }
}
