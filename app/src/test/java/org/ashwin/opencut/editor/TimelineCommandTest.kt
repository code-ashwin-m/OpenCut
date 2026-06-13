package org.ashwin.opencut.editor

import org.ashwin.opencut.core.common.TimeRange
import org.ashwin.opencut.core.common.Timecode
import org.ashwin.opencut.core.timeline.ClipKind
import org.ashwin.opencut.core.timeline.MediaAsset
import org.ashwin.opencut.core.timeline.MediaType
import org.ashwin.opencut.core.timeline.TimelineClip
import org.ashwin.opencut.core.timeline.TimelineFactory
import org.ashwin.opencut.editor.commands.AddClipCommand
import org.ashwin.opencut.editor.commands.SplitClipCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineCommandTest {
    @Test
    fun splitClipCreatesTwoClipsWithContiguousRanges() {
        val project = TimelineFactory.emptyProject("Test Project", createdAtEpochMs = 1L)
        val videoTrack = project.timeline.tracks.first()
        val asset = MediaAsset(
            id = "asset-1",
            uri = "content://media/video/1",
            displayName = "clip.mp4",
            type = MediaType.VIDEO,
            duration = Timecode.fromSeconds(10.0),
        )
        val clip = TimelineClip(
            id = "clip-1",
            trackId = videoTrack.id,
            assetId = asset.id,
            kind = ClipKind.VIDEO,
            sourceRange = TimeRange(Timecode.ZERO, Timecode.fromSeconds(10.0)),
            timelineRange = TimeRange(Timecode.ZERO, Timecode.fromSeconds(10.0)),
        )
        val session = EditorSession(EditorState(project = project.copy(mediaAssets = listOf(asset))))

        session.execute(AddClipCommand(clip))
        session.execute(SplitClipCommand(clipId = clip.id, splitAt = Timecode.fromSeconds(4.0)))

        val clips = session.state.project.timeline.tracks.first().clips
        assertEquals(2, clips.size)
        assertEquals(Timecode.fromSeconds(4.0), clips[0].timelineRange.duration)
        assertEquals(Timecode.fromSeconds(4.0), clips[1].timelineRange.start)
        assertEquals(Timecode.fromSeconds(6.0), clips[1].timelineRange.duration)
    }

    @Test
    fun commandManagerCanUndoAndRedo() {
        val project = TimelineFactory.emptyProject("Test Project", createdAtEpochMs = 1L)
        val videoTrack = project.timeline.tracks.first()
        val clip = TimelineClip(
            id = "clip-1",
            trackId = videoTrack.id,
            assetId = "asset-1",
            kind = ClipKind.VIDEO,
            sourceRange = TimeRange(Timecode.ZERO, Timecode.fromSeconds(3.0)),
            timelineRange = TimeRange(Timecode.ZERO, Timecode.fromSeconds(3.0)),
        )
        val session = EditorSession(EditorState(project = project))

        session.execute(AddClipCommand(clip))
        assertEquals(1, session.state.project.timeline.tracks.first().clips.size)

        assertTrue(session.undo())
        assertEquals(0, session.state.project.timeline.tracks.first().clips.size)

        assertTrue(session.redo())
        assertEquals(1, session.state.project.timeline.tracks.first().clips.size)
    }
}
