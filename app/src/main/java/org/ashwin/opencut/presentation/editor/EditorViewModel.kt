package org.ashwin.opencut.presentation.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.ashwin.opencut.domain.model.EffectSettings
import org.ashwin.opencut.domain.model.ProjectState
import org.ashwin.opencut.domain.repository.ProjectRepository
import org.ashwin.opencut.domain.usecase.ExportVideoUseCase
import javax.inject.Inject

data class EditorUiState(
    val exportProgress: Float = 0f,
    val isExporting: Boolean = false,
    val effectSettings: EffectSettings = EffectSettings()
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val exportVideoUseCase: ExportVideoUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _projectState = MutableStateFlow<ProjectState?>(null)
    val projectState: StateFlow<ProjectState?> = _projectState.asStateFlow()

    private val projectId: String? = savedStateHandle["projectId"]
    private var saveJob: Job? = null

    init {
        projectId?.let { id ->
            viewModelScope.launch {
                val project = projectRepository.getProjectById(id)
                if (project != null) {
                    _projectState.value = project
                    _uiState.value = _uiState.value.copy(effectSettings = project.effectSettings)
                }
            }
        }
    }

    fun startExport(inputUri: Uri, outputPath: String) {
        _uiState.value = _uiState.value.copy(isExporting = true, exportProgress = 0f)
        
        viewModelScope.launch {
            exportVideoUseCase.execute(inputUri, outputPath, _uiState.value.effectSettings)
                .asFlow()
                .collect { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getFloat("progress", 0f)
                            updateProgress(progress)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            finishExport()
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            failExport()
                        }
                        else -> {
                            // Ignored (ENQUEUED, BLOCKED)
                        }
                    }
                }
        }
    }

    fun updateProgress(progress: Float) {
        _uiState.value = _uiState.value.copy(exportProgress = progress)
    }

    private fun updateEffectSettings(updateBlock: (EffectSettings) -> Unit) {
        val currentSettings = _uiState.value.effectSettings
        val newSettings = EffectSettings().apply {
            brightness = currentSettings.brightness
            contrast = currentSettings.contrast
            saturation = currentSettings.saturation
            exposure = currentSettings.exposure
            highlights = currentSettings.highlights
            shadows = currentSettings.shadows
        }
        updateBlock(newSettings)
        _uiState.value = _uiState.value.copy(effectSettings = newSettings)

        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            _projectState.value?.let { currentProject ->
                currentProject.effectSettings = newSettings
                projectRepository.saveProject(currentProject)
            }
        }
    }

    fun updateBrightness(brightness: Float) {
        updateEffectSettings { it.brightness = brightness }
    }

    fun updateContrast(contrast: Float) {
        updateEffectSettings { it.contrast = contrast }
    }

    fun updateExposure(exposure: Float) {
        updateEffectSettings { it.exposure = exposure }
    }

    fun updateHighlights(highlights: Float) {
        updateEffectSettings { it.highlights = highlights }
    }

    fun updateShadows(shadows: Float) {
        updateEffectSettings { it.shadows = shadows }
    }

    fun finishExport() {
        _uiState.value = _uiState.value.copy(isExporting = false, exportProgress = 1f)
    }

    fun failExport() {
        _uiState.value = _uiState.value.copy(isExporting = false)
    }
}