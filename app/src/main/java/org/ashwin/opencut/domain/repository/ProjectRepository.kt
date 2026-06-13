package org.ashwin.opencut.domain.repository

import kotlinx.coroutines.flow.Flow
import org.ashwin.opencut.domain.model.ProjectState

interface ProjectRepository {
    fun getAllProjects(): Flow<List<ProjectState>>
    suspend fun getProjectById(projectId: String): ProjectState?
    suspend fun saveProject(project: ProjectState)
    suspend fun deleteProject(projectId: String)
}
