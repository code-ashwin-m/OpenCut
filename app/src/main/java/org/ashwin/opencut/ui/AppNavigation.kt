package org.ashwin.opencut.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.ashwin.opencut.engine.timeline.Clip
import org.ashwin.opencut.engine.timeline.ColorAdjustments
import org.ashwin.opencut.engine.timeline.ProjectItem
import org.ashwin.opencut.engine.timeline.ProjectManager
import org.ashwin.opencut.engine.timeline.TimelineComposition
import org.ashwin.opencut.engine.timeline.Track
import org.ashwin.opencut.engine.timeline.TrackType
import org.ashwin.opencut.engine.timeline.VideoTransform

@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val projectManager = remember { ProjectManager(context) }

    var projectsList by remember { mutableStateOf(emptyList<ProjectItem>()) }
    var currentActiveProject by remember { mutableStateOf<ProjectItem?>(null) }

    // Read projects from on-device storage upon screen mounting
    LaunchedEffect(Unit) {
        projectsList = projectManager.listProjects()
    }

    if (currentActiveProject == null) {
        // --- 1. PROJECT DASHBOARD LAUNCHER SCREEN ---
        ProjectDashboardScreen(
            projects = projectsList,
            onCreateProject = { name ->
                val newProj = projectManager.createProject(name)
                projectsList = projectManager.listProjects()
                currentActiveProject = newProj
            },
            onOpenProject = { project ->
                currentActiveProject = project
            },
            onDeleteProject = { projectId ->
                projectManager.deleteProject(projectId)
                projectsList = projectManager.listProjects()
            },
            modifier = modifier
        )
    } else {
        // --- 2. PROFESSIONAL 3-PANE TIMELINE WORKSPACE EDITOR ---
        val activeProj = currentActiveProject!!

        var composition by remember { mutableStateOf(createMockTimelineComposition()) }

        EditorScreen(
            initialComposition = activeProj.composition,
            onSeek = { _ -> }, // Native C++ render bridge takes care of rendering
            onBackToDashboard = { updatedComposition ->
                // Save current timeline state to disk inside the project directory before exiting
                projectManager.saveProject(activeProj.id, updatedComposition)

                // Re-fetch project list to show updated thumbnail size & timestamp
                projectsList = projectManager.listProjects()
                currentActiveProject = null
            },
            modifier = modifier
        )
    }
}

/**
 * Helper function to instantiate a high-quality dummy TimelineComposition.
 * Replicates a real-world multi-track editing environment with:
 * - A background music track (AUDIO)
 * - Main A-roll video timeline with cuts (VIDEO)
 * - Graphic title cards (TEXT)
 * - Filter/adjustment overlay layers (OVERLAY)
 */
fun createMockTimelineComposition(): TimelineComposition {
    // 1. Text Overlay Tracks (A Title Card that fades in, and Subtitles)
    val textTrack = Track(
        id = "track_text_1",
        type = TrackType.TEXT,
        isMuted = false,
        isLocked = false,
        clips = listOf(
            Clip(
                id = "clip_title_intro",
                filePath = "Overlay: 'CYBERPUNK 2026'",
                sourceInMs = 0,
                sourceOutMs = 4000,
                timelineStartMs = 1000, // Appears 1s into timeline
                timelineEndMs = 5000,   // Disappears at 5s
                speed = 1.0f,
                transform = VideoTransform(
                    scaleX = 1.2f,
                    scaleY = 1.2f,
                    translationY = -150f // Positioned in the upper half of canvas
                )
            ),
            Clip(
                id = "clip_credit_outro",
                filePath = "Overlay: 'Thanks for watching!'",
                sourceInMs = 0,
                sourceOutMs = 3000,
                timelineStartMs = 24000, // Appears near the end
                timelineEndMs = 28000,
                speed = 1.0f,
                transform = VideoTransform(
                    scaleX = 1.0f,
                    scaleY = 1.0f,
                    translationY = 120f // Lower third position
                )
            )
        )
    )

    // 2. Main Video Tracks (Consecutive video files with transitions/spacing)
    val videoTrack = Track(
        id = "track_video_main",
        type = TrackType.VIDEO,
        isMuted = false,
        isLocked = false,
        clips = listOf(
            Clip(
                id = "clip_intro_cinematic",
                filePath = "assets/videos/intro_cinematic_4k.mp4",
                sourceInMs = 2000,      // Trimmed 2s from raw asset start
                sourceOutMs = 10000,    // Total raw asset selection of 8s
                timelineStartMs = 0,    // Starts immediately at 0s
                timelineEndMs = 8000,   // Duration is 8s
                speed = 1.0f,
                adjustments = ColorAdjustments(
                    brightness = 0.05f, // Slight bright boost
                    contrast = 1.1f,    // Enhanced pop
                    saturation = 1.15f
                )
            ),
            Clip(
                id = "clip_gameplay_action",
                filePath = "assets/videos/apex_legends_60fps.mp4",
                sourceInMs = 15000,     // Cuts deep into gameplay capture
                sourceOutMs = 30000,    // 15 seconds portion
                timelineStartMs = 8000,  // Placed right after intro cinematic
                timelineEndMs = 23000,  // Ends at 23s
                speed = 1.0f,
                transform = VideoTransform(
                    scaleX = 1.0f,
                    scaleY = 1.0f
                ),
                adjustments = ColorAdjustments(
                    contrast = 1.2f,
                    exposure = 0.1f
                )
            ),
            Clip(
                id = "clip_outro_ambient",
                filePath = "assets/videos/neon_city_timelapse.mp4",
                sourceInMs = 0,
                sourceOutMs = 7000,
                timelineStartMs = 23000, // Continuous cut
                timelineEndMs = 30000,   // Final frame at 30s
                speed = 1.0f,
                transform = VideoTransform(
                    scaleX = 1.05f,      // Subtle digital scale up
                    scaleY = 1.05f,
                    rotation = 2.0f      // Intentionally tilted aesthetic
                ),
                adjustments = ColorAdjustments(
                    brightness = -0.1f,  // Moody/darker night aesthetic
                    saturation = 1.3f,   // Saturated neon lights
                    exposure = -0.2f
                )
            )
        )
    )

    // 3. Effects / Color Grading Adjustment Overlays (Vignette, cinematic LUT proxy)
    val overlayTrack = Track(
        id = "track_grade_1",
        type = TrackType.OVERLAY,
        isMuted = false,
        isLocked = false,
        clips = listOf(
            Clip(
                id = "clip_cinematic_luts",
                filePath = "effects/lut_warm_teal.png",
                sourceInMs = 0,
                sourceOutMs = 30000,
                timelineStartMs = 0,     // Covers the entire video duration
                timelineEndMs = 30000,
                speed = 1.0f,
                adjustments = ColorAdjustments(
                    brightness = -0.02f,
                    contrast = 1.05f,
                    saturation = 1.2f,    // Global saturation boost
                    exposure = 0.05f
                )
            )
        )
    )

    // 4. Audio Tracks (Background music track + Voiceover sound)
    val audioTrack = Track(
        id = "track_audio_bg",
        type = TrackType.AUDIO,
        isMuted = false,
        isLocked = false,
        clips = listOf(
            Clip(
                id = "clip_ambient_synthwave",
                filePath = "assets/audio/synthwave_sunset_128kbps.mp3",
                sourceInMs = 0,
                sourceOutMs = 30000,
                timelineStartMs = 0,
                timelineEndMs = 30000,
                speed = 1.0f
            ),
            Clip(
                id = "clip_narrator_intro",
                filePath = "assets/audio/narration_voice_dry.wav",
                sourceInMs = 500,
                sourceOutMs = 4500,
                timelineStartMs = 1500,  // Speaks shortly after intro starts
                timelineEndMs = 5500,
                speed = 1.0f
            )
        )
    )

    // Assemble the tracks into the final timeline container
    return TimelineComposition(
        tracks = listOf(overlayTrack, textTrack, videoTrack, audioTrack),
        durationMs = 30000, // Total project length: 30 seconds
        currentTimeMs = 0,  // Initial playback playhead position
        canvasAspectRatio = 16f / 9f // Standard widescreen layout
    )
}
