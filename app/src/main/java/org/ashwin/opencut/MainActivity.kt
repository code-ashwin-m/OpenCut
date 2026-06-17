package org.ashwin.opencut // FIXED: Aligned package naming with NDK C++ JNI linkage namespaces

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

// --- Domain Models ---
data class TimelineClip(
    val id: String,
    val assetUri: String,
    val name: String,
    val durationMs: Long
)

data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val aspectRatio: String, // e.g., "16:9", "9:16", "1:1"
    val videoUri: String? = null, // The currently active/selected video asset URI
    val assets: List<String> = emptyList(), // The list of all imported assets for this project
    val timelineClips: List<TimelineClip> = emptyList() // The clips arranged sequentially on our single track
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

    // Track if we lost permissions to the video URI
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
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(videoUri, takeFlags)

                    val uriString = videoUri.toString()
                    val currentAssets = screen.project.assets.toMutableList()
                    if (!currentAssets.contains(uriString)) {
                        currentAssets.add(uriString)
                    }

                    val activeUri = screen.project.videoUri ?: uriString
                    val updatedProject = screen.project.copy(
                        videoUri = activeUri,
                        assets = currentAssets
                    )

                    if (screen.project.videoUri == null) {
                        currentFd?.close()
                        currentFd = contentResolver.openFileDescriptor(videoUri, "r")
                        currentFd?.let { pfd ->
                            nativeEngine.setDataSource(pfd.fd)
                        }
                        isMediaAccessible = true
                    }

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
        isMediaAccessible = true
        currentScreen = Screen.Editor(project)
    }

    private fun loadProjectVideo(context: Context, project: Project) {
        project.videoUri?.let { uriString ->
            try {
                currentFd?.close()
                val uri = Uri.parse(uriString)
                currentFd = context.contentResolver.openFileDescriptor(uri, "r")
                currentFd?.let { pfd ->
                    nativeEngine.setDataSource(pfd.fd)
                    isMediaAccessible = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isMediaAccessible = false
            }
        }
    }

    // Safely extract the video clip's duration in milliseconds using MediaMetadataRetriever
    private fun getVideoDurationMs(context: Context, uriString: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uriString))
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLong() ?: 5000L // Default fallback to 5 seconds if query fails
        } catch (e: Exception) {
            e.printStackTrace()
            5000L
        } finally {
            retriever.release()
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

                val assetsArray = JSONArray()
                project.assets.forEach { assetsArray.put(it) }
                put("assets", assetsArray)

                // Serialize Timeline Clips
                val timelineArray = JSONArray()
                project.timelineClips.forEach { clip ->
                    val clipJson = JSONObject().apply {
                        put("id", clip.id)
                        put("assetUri", clip.assetUri)
                        put("name", clip.name)
                        put("durationMs", clip.durationMs)
                    }
                    timelineArray.put(clipJson)
                }
                put("timelineClips", timelineArray)
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

                val assetsList = mutableListOf<String>()
                if (json.has("assets")) {
                    val arr = json.getJSONArray("assets")
                    for (i in 0 until arr.length()) {
                        assetsList.add(arr.getString(i))
                    }
                }

                val videoUri = if (json.isNull("videoUri")) null else json.getString("videoUri")
                if (assetsList.isEmpty() && videoUri != null) {
                    assetsList.add(videoUri)
                }

                // Deserialize Timeline Clips
                val timelineClipsList = mutableListOf<TimelineClip>()
                if (json.has("timelineClips")) {
                    val arr = json.getJSONArray("timelineClips")
                    for (i in 0 until arr.length()) {
                        val clipJson = arr.getJSONObject(i)
                        timelineClipsList.add(
                            TimelineClip(
                                id = clipJson.getString("id"),
                                assetUri = clipJson.getString("assetUri"),
                                name = clipJson.getString("name"),
                                durationMs = clipJson.getLong("durationMs")
                            )
                        )
                    }
                }

                val project = Project(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    createdAt = json.getLong("createdAt"),
                    aspectRatio = json.getString("aspectRatio"),
                    videoUri = videoUri,
                    assets = assetsList,
                    timelineClips = timelineClipsList
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
                        text = "Aspect Ratio: ${project.aspectRatio} | Assets: ${project.assets.size} | Track Clips: ${project.timelineClips.size}",
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

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun EditorScreen(project: Project, onBackToProjects: () -> Unit) {
        val context = LocalContext.current

        // 60FPS Microsecond Clock synchronization loop
        var currentPlayheadUs by remember { mutableStateOf(0L) }
        LaunchedEffect(isPlaying) {
            if (isPlaying) {
                while (isPlaying) {
                    currentPlayheadUs = nativeEngine.getCurrentPositionUs()
                    delay(16) // ~60fps poll ticker
                }
            }
        }

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Top Half Split View: Asset Panel on Left, Previewer on Right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
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
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "Double-tap an asset to add to track.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
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
                                            .combinedClickable(
                                                onClick = {
                                                    if (isPlaying) {
                                                        nativeEngine.pause()
                                                        isPlaying = false
                                                    }
                                                    val updatedProject = project.copy(videoUri = assetUriString)
                                                    saveProjectToFile(context, updatedProject)
                                                    currentScreen = Screen.Editor(updatedProject)
                                                    loadProjectVideo(context, updatedProject)
                                                },
                                                onDoubleClick = {
                                                    // On double click, calculate metadata duration and append to timeline track
                                                    val duration = getVideoDurationMs(context, assetUriString)
                                                    val newClip = TimelineClip(
                                                        id = System.currentTimeMillis().toString(),
                                                        assetUri = assetUriString,
                                                        name = assetName,
                                                        durationMs = duration
                                                    )
                                                    val updatedClips = project.timelineClips.toMutableList().apply {
                                                        add(newClip)
                                                    }
                                                    val updatedProject = project.copy(
                                                        timelineClips = updatedClips,
                                                        videoUri = project.videoUri ?: assetUriString
                                                    )
                                                    saveProjectToFile(context, updatedProject)
                                                    currentScreen = Screen.Editor(updatedProject)
                                                    refreshProjectsList(context)
                                                }
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play icon",
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

                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // --- PREVIEW WINDOW ---
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
                                        text = "Please re-attach the file to resume editing.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Playback Controls Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                                .padding(8.dp),
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
                                modifier = Modifier.width(160.dp)
                            ) {
                                Text(if (isPlaying) "Pause" else "Play")
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // --- SINGLE TRACK TIMELINE PANEL ---
                // FIXED: Decreased from 240.dp to 180.dp to prevent pushing off the viewport on landscape layouts
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(0.5.dp))
                        .padding(top = 8.dp)
                ) {
                    // Timeline Metadata Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Timeline (Single Track)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val totalDurationMs = project.timelineClips.sumOf { it.durationMs }
                            Text(
                                text = "Total Time: ${totalDurationMs / 1000}s | Playhead: ${currentPlayheadUs / 1000000}s",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            TextButton(
                                onClick = {
                                    val updatedProject = project.copy(timelineClips = emptyList())
                                    saveProjectToFile(context, updatedProject)
                                    currentScreen = Screen.Editor(updatedProject)
                                    refreshProjectsList(context)
                                },
                                enabled = project.timelineClips.isNotEmpty()
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear Track", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Horizontal Scroll Track Area
                    val timelineScrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF1E1E1E))
                            .horizontalScroll(timelineScrollState)
                    ) {
                        // Drawing variables: 1 second = 40.dp density
                        val dpPerSec = 40.dp

                        val totalDurationMs = project.timelineClips.sumOf { it.durationMs }
                        // FIXED: Replaced standard Double evaluation to ensure compiler safety across toolchains
                        // Also, guaranteed that timeline track spans at least 1200.dp so ruler ticks don't collapse on screen setup
                        val trackWidth = maxOf((totalDurationMs * 0.04f).dp, 1200.dp)

                        Box(
                            modifier = Modifier
                                .width(trackWidth)
                                .fillMaxHeight()
                                .drawWithContent {
                                    drawContent()

                                    val playheadMs = currentPlayheadUs / 1000
                                    val playheadOffsetPx = (playheadMs * 0.04f).dp.toPx()

                                    drawLine(
                                        color = Color.Red,
                                        start = Offset(x = playheadOffsetPx, y = 0f),
                                        end = Offset(x = playheadOffsetPx, y = size.height),
                                        strokeWidth = 3.dp.toPx()
                                    )

                                    drawCircle(
                                        color = Color.Red,
                                        radius = 6.dp.toPx(),
                                        center = Offset(x = playheadOffsetPx, y = 8.dp.toPx())
                                    )
                                }
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Time Ruler ticks
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(24.dp)
                                        .background(Color.Black.copy(alpha = 0.3f)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val tickCount = maxOf((totalDurationMs / 1000).toInt(), 30) // Render at least 30 ticks for default screen width span
                                    for (i in 0..tickCount) {
                                        Box(
                                            modifier = Modifier
                                                .width(dpPerSec)
                                                .fillMaxHeight(),
                                            contentAlignment = Alignment.BottomStart
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .height(8.dp)
                                                    .background(Color.Gray)
                                            )
                                            Text(
                                                text = "${i}s",
                                                fontSize = 10.sp,
                                                color = Color.Gray,
                                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                                            )
                                        }
                                    }
                                }

                                // Single Sequential Track Row Container
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (project.timelineClips.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight()
                                                .padding(horizontal = 16.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(
                                                text = "No clips on track. Double-tap items inside the Asset Panel to assemble your timeline.",
                                                color = Color.DarkGray,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    } else {
                                        project.timelineClips.forEachIndexed { index, clip ->
                                            val clipWidth = (clip.durationMs * 0.04f).dp

                                            Card(
                                                shape = RoundedCornerShape(4.dp),
                                                colors = CardDefaults.cardColors(
//                                                    containerColor = MaterialTheme.copy(colorScheme = darkColorScheme()).colorScheme.primaryContainer.copy(alpha = 0.85f)
                                                ),
                                                modifier = Modifier
                                                    .width(clipWidth)
                                                    .fillMaxHeight()
                                                    .padding(horizontal = 1.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(6.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(
                                                            text = clip.name,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            textAlign = TextAlign.Center
                                                        )
                                                        Text(
                                                            text = "${clip.durationMs / 1000}s",
                                                            fontSize = 10.sp,
                                                            color = Color.LightGray
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
    external fun getCurrentPositionUs(): Long // Real-time playhead clock linkage
}