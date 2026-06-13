package org.ashwin.opencut.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ashwin.opencut.domain.model.ProjectState
import org.ashwin.opencut.domain.repository.ProjectRepository
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProjectListViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<ProjectState>>(emptyList())
    val projects: StateFlow<List<ProjectState>> = _projects.asStateFlow()

    init {
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            projectRepository.getAllProjects().collect { projectList ->
                _projects.value = projectList
            }
        }
    }

    fun createProject(name: String, onProjectCreated: (String) -> Unit) {
        viewModelScope.launch {
            val projectId = UUID.randomUUID().toString()
            val newProject = ProjectState(
                projectId = projectId,
                projectName = name
            )
            projectRepository.saveProject(newProject)
            loadProjects()
            onProjectCreated(projectId)
        }
    }
}
