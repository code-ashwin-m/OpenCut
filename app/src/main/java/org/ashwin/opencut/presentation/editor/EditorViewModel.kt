package org.ashwin.opencut.presentation.editor;

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class EditorViewModel : ViewModel() {

    var exportProgress by mutableFloatStateOf(0f)
        private set

    var isExporting by mutableStateOf(false)
        private set

    fun startExport() {
        isExporting = true
        exportProgress = 0f
    }

    fun updateProgress(
        progress: Float
    ) {
        exportProgress = progress
    }

    fun finishExport() {
        exportProgress = 1f
        isExporting = false
    }

    fun failExport() {
        isExporting = false
    }
}