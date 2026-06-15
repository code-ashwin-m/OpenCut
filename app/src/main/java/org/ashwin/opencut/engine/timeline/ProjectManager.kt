package org.ashwin.opencut.engine.timeline

import android.content.Context
import java.io.File
import java.util.UUID

data class ProjectItem(
    val id: String,
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val composition: TimelineComposition
)

class ProjectManager(private val context: Context) {

    // Base Directory: /storage/emulated/0/Android/data/com.editor/files/projects/
    private val projectsBaseDir: File by lazy {
        File(context.getExternalFilesDir(null), "projects").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Scans the base projects directory and deserializes each project configuration
     */
    fun listProjects(): List<ProjectItem> {
        val projectFolders = projectsBaseDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        val projects = mutableListOf<ProjectItem>()

        projectFolders.forEach { folder ->
            val jsonFile = File(folder, "project.json")
            if (jsonFile.exists()) {
                try {
                    val jsonStr = jsonFile.readText()
                    val composition = ProjectSerializer.deserialize(jsonStr)

                    // REAL FILE CALCULATION: Recurse directories to sum actual storage footprint
                    val actualFolderSize = calculateDirectorySize(folder)

                    projects.add(
                        ProjectItem(
                            id = folder.name,
                            name = folder.name.substringBeforeLast("_"),
                            lastModified = jsonFile.lastModified(), // Actual system timestamp
                            sizeBytes = actualFolderSize,
                            composition = composition
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return projects.sortedByDescending { it.lastModified }
    }

    /**
     * Allocates a clean project folder on disk and returns its structured paths
     */
    fun createProject(projectName: String): ProjectItem {
        val sanitizedName = projectName.replace(Regex("[^a-zA-Z0-9]"), "")
        val projectId = "${sanitizedName}_${UUID.randomUUID().toString().take(6)}"

        val projectDir = File(projectsBaseDir, projectId)
        val proxyDir = File(projectDir, "proxies")
        val cacheDir = File(projectDir, "cache")

        projectDir.mkdirs()
        proxyDir.mkdirs()
        cacheDir.mkdirs()

        // Create an initial empty multi-track timeline configuration (2 tracks by default)
        val initialComposition = TimelineComposition(
            tracks = listOf(
                Track(id = "video_track_1", type = TrackType.VIDEO),
                Track(id = "audio_track_1", type = TrackType.AUDIO)
            )
        )

        val jsonFile = File(projectDir, "project.json")
        jsonFile.writeText(ProjectSerializer.serialize(initialComposition))

        return ProjectItem(
            id = projectId,
            name = projectName,
            lastModified = jsonFile.lastModified(),
            sizeBytes = 0,
            composition = initialComposition
        )
    }

    /**
     * Saves changes to the master project.json layout
     */
    fun saveProject(projectId: String, composition: TimelineComposition) {
        val projectDir = File(projectsBaseDir, projectId)
        if (projectDir.exists()) {
            val jsonFile = File(projectDir, "project.json")
            jsonFile.writeText(ProjectSerializer.serialize(composition))
        }
    }

    /**
     * Safely and recursively removes a project and all its proxy/cache memory artifacts
     */
    fun deleteProject(projectId: String) {
        val projectDir = File(projectsBaseDir, projectId)
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
    }

    private fun calculateDirectorySize(directory: File): Long {
        var size: Long = 0
        val files = directory.listFiles()
        if (files != null) {
            for (file in files) {
                size += if (file.isDirectory) {
                    calculateDirectorySize(file)
                } else {
                    file.length()
                }
            }
        }
        return size
    }
}
