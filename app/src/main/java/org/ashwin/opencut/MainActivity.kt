package org.ashwin.opencut


import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

// --- Domain Models ---
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val aspectRatio: String, // e.g., "16:9", "9:16", "1:1"
    val videoUri: String? = null, // The currently active/selected video asset URI
    val assets: List<String> = emptyList() // The list of all imported assets for this project
)

// --- Navigation States ---
sealed class Screen {
    object ProjectsList : Screen()
    data class Editor(val project: Project) : Screen()
}

class MainActivity : ComponentActivity() {

    private val nativeEngine = NativeEngine()
    private var isPlaying by mutableStateOf(false)
    private var currentFd: ParcelFileDescriptor? = null

    // Track if we lost permissions to the video URI (e.g., if the user deleted the original file)
    private var isMediaAccessible by mutableStateOf(true)

    // Navigation & Project state
    private var currentScreen by mutableStateOf<Screen>(Screen.ProjectsList)
    private val projectsList = mutableStateListOf<Project>()

    // Launcher configured to request a persistent virtual file path (URI) and import it to the Asset Panel
    private val selectVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { videoUri ->
            val screen = currentScreen
            if (screen is Screen.Editor) {
                try {
                    // 1. Take persistable read permission so we can open this URI even after device reboots
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(videoUri, takeFlags)

                    val uriString = videoUri.toString()
                    val currentAssets = screen.project.assets.toMutableList()
                    if (!currentAssets.contains(uriString)) {
                        currentAssets.add(uriString)
                    }

                    // If no asset was previously active, make this newly imported one the active selection
                    val activeUri = screen.project.videoUri ?: uriString

                    val updatedProject = screen.project.copy(
                        videoUri = activeUri,
                        assets = currentAssets
                    )

                    // If setting active for the first time, immediately pass the raw file descriptor to C++
                    if (screen.project.videoUri == null) {
                        currentFd?.close()
                        currentFd = contentResolver.openFileDescriptor(videoUri, "r")
                        currentFd?.let { pfd ->
                            nativeEngine.setDataSource(pfd.fd)
                        }
                        isMediaAccessible = true
                    }

                    // Save the updated asset array and update states
                    saveProjectToFile(this@MainActivity, updatedProject)
                    currentScreen = Screen.Editor(updatedProject)
                    refreshProjectsList(this@MainActivity)

                } catch (e: Exception) {
                    e.printStackTrace()
                    isMediaAccessible = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load existing projects from disk on startup
        refreshProjectsList(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val screen = currentScreen) {
                        is Screen.ProjectsList -> ProjectsListScreen(
                            projects = projectsList,
                            onCreateProject = { name, ratio ->
                                val newProject = Project(
                                    id = System.currentTimeMillis().toString(),
                                    name = name,
                                    createdAt = System.currentTimeMillis(),
                                    aspectRatio = ratio
                                )
                                saveProjectToFile(this, newProject)
                                refreshProjectsList(this)
                                openProject(newProject)
                            },
                            onOpenProject = { openProject(it) },
                            onDeleteProject = { deleteProject(this, it) }
                        )
                        is Screen.Editor -> EditorScreen(
                            project = screen.project,
                            onBackToProjects = {
                                if (isPlaying) {
                                    nativeEngine.pause()
                                    isPlaying = false
                                }
                                currentScreen = Screen.ProjectsList
                            }
                        )
                    }
                }
            }
        }
    }

    private fun openProject(project: Project) {
        isMediaAccessible = true // Reset permission state before loading
        currentScreen = Screen.Editor(project)
    }

    // Convert the stored URI string to an FD and send it to C++ (Zero copying!)
    private fun loadProjectVideo(context: Context, project: Project) {
        project.videoUri?.let { uriString ->
            try {
                currentFd?.close()
                val uri = Uri.parse(uriString)

                // Try to resolve the URI to a Read-Only File Descriptor
                currentFd = context.contentResolver.openFileDescriptor(uri, "r")
                currentFd?.let { pfd ->
                    nativeEngine.setDataSource(pfd.fd)
                    isMediaAccessible = true
                }
            } catch (e: SecurityException) {
                // Triggers gracefully if persistable permissions were revoked or the file was deleted/moved
                e.printStackTrace()
                isMediaAccessible = false
            } catch (e: Exception) {
                e.printStackTrace()
                isMediaAccessible = false
            }
        }
    }

    // --- Local JSON Storage Helpers ---
    private fun getProjectsFolder(context: Context): File {
        val folder = File(context.getExternalFilesDir(null), "projects")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    private fun saveProjectToFile(context: Context, project: Project) {
        val file = File(getProjectsFolder(context), "${project.id}.json")
        try {
            val json = JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
                put("createdAt", project.createdAt)
                put("aspectRatio", project.aspectRatio)
                put("videoUri", project.videoUri ?: JSONObject.NULL)

                // Save the assets list as a JSON array
                val assetsArray = JSONArray()
                project.assets.forEach { assetsArray.put(it) }
                put("assets", assetsArray)
            }
            FileWriter(file).use { writer ->
                writer.write(json.toString(4))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refreshProjectsList(context: Context) {
        projectsList.clear()
        val folder = getProjectsFolder(context)
        val files = folder.listFiles { _, name -> name.endsWith(".json") }
        files?.forEach { file ->
            try {
                val content = file.readText()
                val json = JSONObject(content)

                // Parse assets list
                val assetsList = mutableListOf<String>()
                if (json.has("assets")) {
                    val arr = json.getJSONArray("assets")
                    for (i in 0 until arr.length()) {
                        assetsList.add(arr.getString(i))
                    }
                }

                val videoUri = if (json.isNull("videoUri")) null else json.getString("videoUri")

                // Backwards compatibility safeguard: If assets is empty but a single video was attached
                if (assetsList.isEmpty() && videoUri != null) {
                    assetsList.add(videoUri)
                }

                val project = Project(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    createdAt = json.getLong("createdAt"),
                    aspectRatio = json.getString("aspectRatio"),
                    videoUri = videoUri,
                    assets = assetsList
                )
                projectsList.add(project)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        projectsList.sortByDescending { it.createdAt }
    }

    private fun deleteProject(context: Context, project: Project) {
        val file = File(getProjectsFolder(context), "${project.id}.json")
        if (file.exists()) {
            file.delete()
        }
        refreshProjectsList(context)
    }

    // --- UI Composables ---

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ProjectsListScreen(
        projects: List<Project>,
        onCreateProject: (name: String, ratio: String) -> Unit,
        onOpenProject: (Project) -> Unit,
        onDeleteProject: (Project) -> Unit
    ) {
        var showCreateDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("My Video Projects", fontWeight = FontWeight.Bold) }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "New Project")
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (projects.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No projects yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showCreateDialog = true }) {
                            Text("Create Your First Project")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(projects) { project ->
                            ProjectCard(
                                project = project,
                                onClick = { onOpenProject(project) },
                                onDelete = { onDeleteProject(project) }
                            )
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreateProjectDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name, ratio ->
                    onCreateProject(name, ratio)
                    showCreateDialog = false
                }
            )
        }
    }

    @Composable
    fun ProjectCard(project: Project, onClick: () -> Unit, onDelete: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Aspect Ratio: ${project.aspectRatio} | Assets: ${project.assets.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Project",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CreateProjectDialog(onDismiss: () -> Unit, onConfirm: (name: String, ratio: String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var selectedRatio by remember { mutableStateOf("16:9") }
        val aspectRatios = listOf("16:9", "9:16", "1:1")

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New Editing Project") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Project Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select Aspect Ratio:", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        aspectRatios.forEach { ratio ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { selectedRatio = ratio }
                            ) {
                                RadioButton(
                                    selected = (selectedRatio == ratio),
                                    onClick = { selectedRatio = ratio }
                                )
                                Text(ratio)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { if (name.isNotBlank()) onConfirm(name, selectedRatio) },
                    enabled = name.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun EditorScreen(project: Project, onBackToProjects: () -> Unit) {
        val context = LocalContext.current

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(project.name) },
                    navigationIcon = {
                        IconButton(onClick = onBackToProjects) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            // Tablet layout: horizontal split. Left side is the Asset Panel; Right side is the Previewer & Controls
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // --- ASSET PANEL SIDEBAR ---
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Project Assets",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = { selectVideoLauncher.launch(arrayOf("video/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Import")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Media")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (project.assets.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No media imported yet.\nTap 'Import Media' to add video clips.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(project.assets) { assetUriString ->
                                val isSelected = assetUriString == project.videoUri
                                val assetName = remember(assetUriString) {
                                    Uri.parse(assetUriString).lastPathSegment ?: "Video Clip"
                                }

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // Handle selecting and loading different media assets on tap
                                            if (isPlaying) {
                                                nativeEngine.pause()
                                                isPlaying = false
                                            }
                                            val updatedProject = project.copy(videoUri = assetUriString)
                                            saveProjectToFile(context, updatedProject)
                                            currentScreen = Screen.Editor(updatedProject)
                                            loadProjectVideo(context, updatedProject)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Active asset marker",
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = assetName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = if (isSelected) "Active Preview" else "Tap to preview",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Vertical border divider separating panels
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // --- PREVIEW WINDOW & TIMELINE/PLAYBACK CONTROLS ---
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (project.videoUri == null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No active media in player",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "Import and tap a clip in the Asset Panel to preview.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.DarkGray
                                )
                            }
                        } else if (isMediaAccessible) {
                            AndroidView(
                                factory = { ctx ->
                                    SurfaceView(ctx).apply {
                                        holder.addCallback(object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(holder: SurfaceHolder) {
                                                nativeEngine.setSurface(holder.surface)
                                                loadProjectVideo(context, project)
                                            }
                                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                                nativeEngine.releaseSurface()
                                            }
                                        })
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Friendly Warning UI State instead of crashing
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Media File Inaccessible",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "The video file may have been moved, deleted, or its temporary access token expired. Please re-attach the file to resume editing.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    // Bottom Control / Media bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (isPlaying) {
                                    nativeEngine.pause()
                                } else {
                                    nativeEngine.play()
                                }
                                isPlaying = !isPlaying
                            },
                            enabled = project.videoUri != null && isMediaAccessible,
                            modifier = Modifier.width(200.dp)
                        ) {
                            Text(if (isPlaying) "Pause" else "Play")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nativeEngine.release()
        currentFd?.close()
    }
}

// Thin JNI Wrapper
class NativeEngine {
    init {
        System.loadLibrary("opencut")
    }

    external fun setSurface(surface: Surface)
    external fun releaseSurface()
    external fun setDataSource(fd: Int)
    external fun play()
    external fun pause()
    external fun release()
}