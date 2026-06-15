package org.ashwin.opencut.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ashwin.opencut.engine.timeline.MediaAsset

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaLibraryPanel(
    onAssetSelected: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current


    // 1. Initial set of mock system-resourced videos
    var mediaList by remember { mutableStateOf(emptyList<MediaAsset>()) }

//    var mediaList by remember {
//        mutableStateOf(
//            listOf(
//                MediaAsset(
//                    id = "res_1",
//                    displayName = "cinematic_drone_4k.mp4",
//                    durationMs = 15000,
//                    resolution = "3840x2160",
//                    fps = 60,
//                    localUri = "android.resource://com.editor/raw/cinematic_drone",
//                    thumbnailColor = 0xFF4A90E2, // Ice Blue
//                    fileSizeBytes = 45000000
//                ),
//                MediaAsset(
//                    id = "res_2",
//                    displayName = "urban_street_vibe.mp4",
//                    durationMs = 8200,
//                    resolution = "1920x1080",
//                    fps = 30,
//                    localUri = "android.resource://com.editor/raw/urban_street",
//                    thumbnailColor = 0xFFD0021B, // Neon Red
//                    fileSizeBytes = 12000000
//                ),
//                MediaAsset(
//                    id = "res_3",
//                    displayName = "skate_park_slowmo.mp4",
//                    durationMs = 24000,
//                    resolution = "1920x1080",
//                    fps = 120,
//                    localUri = "android.resource://com.editor/raw/skate_slowmo",
//                    thumbnailColor = 0xFFF5A623, // Sunrise Orange
//                    fileSizeBytes = 38000000
//                ),
//                MediaAsset(
//                    id = "res_4",
//                    displayName = "talking_head_interview.mp4",
//                    durationMs = 42000,
//                    resolution = "3840x2160",
//                    fps = 24,
//                    localUri = "android.resource://com.editor/raw/interview",
//                    thumbnailColor = 0xFF7ED321, // Forest Green
//                    fileSizeBytes = 89000000
//                )
//            )
//        )
//    }

    // 2. Real SAF storage photo-picker contract integration (Triggered when clicking the "+" import button)

    // Real system storage content picker contract
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            val resolvedAsset = extractMediaMetadata(context, selectedUri)
            if (resolvedAsset != null) {
                mediaList = mediaList + resolvedAsset
            }
        }
    }

//    val mediaPickerLauncher = rememberLauncherForActivityResult(
//        contract = ActivityResultContracts.GetContent()
//    ) { uri ->
//        uri?.let { selectedUri ->
//            // Simulating parsing of metadata properties from actual ContentResolver
//            val newLocalAsset = MediaAsset(
//                id = "import_${System.currentTimeMillis()}",
//                displayName = selectedUri.lastPathSegment ?: "imported_media.mp4",
//                durationMs = 10000, // Hardcoded fallback for stub model, can be updated via MediaMetadataRetriever
//                resolution = "1080p",
//                fps = 30,
//                localUri = selectedUri.toString(),
//                thumbnailColor = 0xFF9B59B6, // Amethyst purple
//                fileSizeBytes = 1024 * 1024 * 5
//            )
//            mediaList = mediaList + newLocalAsset
//        }
//    }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Videos", "Audio", "Transitions")

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color(0xFF141416))
            .padding(12.dp)
    ) {
        // Row header containing the Action Trigger to import new local media files
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Media Library",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            IconButton(
                onClick = { mediaPickerLauncher.launch("video/*") },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color(0xFF007AFF),
                    contentColor = Color.White
                ),
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Import native media",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Horizontal Category Tab Bar
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            divider = {},
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 12.sp) }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                // Interactive Grid Rendering imported Media
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(mediaList, key = { it.id }) { asset ->
                        MediaAssetCard(
                            asset = asset,
                            onAssetDoubleClicked = { onAssetSelected(asset) }
                        )
                    }
                }
            }
            1 -> PlaceholderTabContent("Audio elements will import here.")
            2 -> PlaceholderTabContent("Video transitions registry.")
        }
    }
}

// REAL METADATA EXTRACTOR IMPLEMENTATION (Using MediaMetadataRetriever)
private fun extractMediaMetadata(context: Context, uri: Uri): MediaAsset? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)

        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)

        val durationMs = durationStr?.toLongOrNull() ?: 0L
        val width = widthStr?.toIntOrNull() ?: 1920
        val height = heightStr?.toIntOrNull() ?: 1080
        val fps = fpsStr?.toFloatOrNull()?.toInt() ?: 30

        // Read real size descriptor via Context Resolver InputStream
        var sizeInBytes: Long = 0
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            sizeInBytes = it.length
        }

        val displayName = uri.lastPathSegment?.substringAfterLast("/") ?: "imported_video.mp4"

        MediaAsset(
            id = "asset_${System.currentTimeMillis()}",
            displayName = displayName,
            durationMs = durationMs,
            resolution = "${width}x${height}",
            fps = fps,
            localUri = uri.toString(),
            thumbnailColor = 0xFF2C2C2E,
            fileSizeBytes = sizeInBytes
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        retriever.release()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaAssetCard(
    asset: MediaAsset,
    onAssetDoubleClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E20)),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onDoubleClick = onAssetDoubleClicked
            )
    ) {
        Column {
            // Simulated Thumbnail Preview Canvas Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(Color(asset.thumbnailColor))
            ) {
                // Asset specifications stamp
                Text(
                    text = "${asset.resolution} • ${asset.fps}fps",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )

                // Render dynamic clip play duration right aligned
                Text(
                    text = asset.durationString,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // Asset Title Tag Metadata
            PaddingValues(8.dp).let { padding ->
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = asset.displayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Size: %.2f MB".format(asset.fileSizeBytes.toFloat() / (1024 * 1024)),
                        fontSize = 9.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderTabContent(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.DarkGray,
            fontSize = 12.sp
        )
    }
}
