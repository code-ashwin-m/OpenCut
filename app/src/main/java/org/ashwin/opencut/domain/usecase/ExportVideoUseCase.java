package org.ashwin.opencut.domain.usecase;

import android.content.Context;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import dagger.hilt.android.qualifiers.ApplicationContext;
import org.ashwin.opencut.domain.model.EffectSettings;
import org.ashwin.opencut.worker.VideoExportWorker;

import javax.inject.Inject;

public class ExportVideoUseCase {

    private final Context context;

    @Inject
    public ExportVideoUseCase(@ApplicationContext Context context) {
        this.context = context;
    }

    public LiveData<WorkInfo> execute(Uri inputUri, String outputPath, EffectSettings effectSettings) {
        Data inputData = new Data.Builder()
                .putString(VideoExportWorker.KEY_INPUT_URI, inputUri.toString())
                .putString(VideoExportWorker.KEY_OUTPUT_PATH, outputPath)
                .putFloat(VideoExportWorker.KEY_BRIGHTNESS, effectSettings.brightness)
                .build();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(VideoExportWorker.class)
                .setInputData(inputData)
                .build();

        WorkManager.getInstance(context).enqueue(workRequest);

        return WorkManager.getInstance(context).getWorkInfoByIdLiveData(workRequest.getId());
    }
}
