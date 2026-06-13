package org.ashwin.opencut.data.repository

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.ashwin.opencut.domain.model.ProjectState
import org.ashwin.opencut.domain.repository.ProjectRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileProjectRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) : ProjectRepository {

    private val projectsDir = File(context.filesDir, "projects").apply {
        if (!exists()) mkdirs()
    }

    override fun getAllProjects(): Flow<List<ProjectState>> = callbackFlow {
        val projects = loadAllProjects()
        trySend(projects)
        awaitClose { }
    }

    private suspend fun loadAllProjects(): List<ProjectState> = withContext(Dispatchers.IO) {
        val files = projectsDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        files.mapNotNull { file ->
            try {
                val json = file.readText()
                gson.fromJson(json, ProjectState::class.java)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun getProjectById(projectId: String): ProjectState? = withContext(Dispatchers.IO) {
        val file = File(projectsDir, "$projectId.json")
        if (!file.exists()) return@withContext null
        try {
            gson.fromJson(file.readText(), ProjectState::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveProject(project: ProjectState) = withContext(Dispatchers.IO) {
        project.updatedAt = System.currentTimeMillis()
        val file = File(projectsDir, "${project.projectId}.json")
        val json = gson.toJson(project)
        file.writeText(json)
    }

    override suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        val file = File(projectsDir, "$projectId.json")
        if (file.exists()) {
            file.delete()
        }
    }
}
