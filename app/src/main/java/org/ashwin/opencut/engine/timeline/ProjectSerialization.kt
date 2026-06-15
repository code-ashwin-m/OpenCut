package org.ashwin.opencut.engine.timeline

import org.json.JSONArray
import org.json.JSONObject

object ProjectSerializer {

    fun serialize(composition: TimelineComposition): String {
        val root = JSONObject()
        root.put("durationMs", composition.durationMs)
        root.put("currentTimeMs", composition.currentTimeMs)
        root.put("canvasAspectRatio", composition.canvasAspectRatio)

        val tracksArray = JSONArray()
        composition.tracks.forEach { track ->
            val trackObj = JSONObject()
            trackObj.put("id", track.id)
            trackObj.put("type", track.type.name)
            trackObj.put("isMuted", track.isMuted)
            trackObj.put("isLocked", track.isLocked)

            val clipsArray = JSONArray()
            track.clips.forEach { clip ->
                val clipObj = JSONObject()
                clipObj.put("id", clip.id)
                clipObj.put("filePath", clip.filePath)
                clipObj.put("sourceInMs", clip.sourceInMs)
                clipObj.put("sourceOutMs", clip.sourceOutMs)
                clipObj.put("timelineStartMs", clip.timelineStartMs)
                clipObj.put("timelineEndMs", clip.timelineEndMs)
                clipObj.put("speed", clip.speed)

                // Transform Object
                val transformObj = JSONObject()
                transformObj.put("scaleX", clip.transform.scaleX)
                transformObj.put("scaleY", clip.transform.scaleY)
                transformObj.put("rotation", clip.transform.rotation)
                transformObj.put("translationX", clip.transform.translationX)
                transformObj.put("translationY", clip.transform.translationY)
                clipObj.put("transform", transformObj)

                // Adjustments Object
                val adjustObj = JSONObject()
                adjustObj.put("brightness", clip.adjustments.brightness)
                adjustObj.put("contrast", clip.adjustments.contrast)
                adjustObj.put("saturation", clip.adjustments.saturation)
                adjustObj.put("exposure", clip.adjustments.exposure)
                clipObj.put("adjustments", adjustObj)

                clipsArray.put(clipObj)
            }
            trackObj.put("clips", clipsArray)
            tracksArray.put(trackObj)
        }
        root.put("tracks", tracksArray)
        return root.toString(2)
    }

    fun deserialize(jsonStr: String): TimelineComposition {
        val root = JSONObject(jsonStr)
        val durationMs = root.optLong("durationMs", 0)
        val currentTimeMs = root.optLong("currentTimeMs", 0)
        val canvasAspectRatio = root.optDouble("canvasAspectRatio", 1.777).toFloat()

        val tracksList = mutableListOf<Track>()
        val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracksArray.length()) {
            val trackObj = tracksArray.getJSONObject(i)
            val id = trackObj.getString("id")
            val type = TrackType.valueOf(trackObj.getString("type"))
            val isMuted = trackObj.optBoolean("isMuted", false)
            val isLocked = trackObj.optBoolean("isLocked", false)

            val clipsList = mutableListOf<Clip>()
            val clipsArray = trackObj.optJSONArray("clips") ?: JSONArray()
            for (j in 0 until clipsArray.length()) {
                val clipObj = clipsArray.getJSONObject(j)

                val transformObj = clipObj.optJSONObject("transform") ?: JSONObject()
                val transform = VideoTransform(
                    scaleX = transformObj.optDouble("scaleX", 1.0).toFloat(),
                    scaleY = transformObj.optDouble("scaleY", 1.0).toFloat(),
                    rotation = transformObj.optDouble("rotation", 0.0).toFloat(),
                    translationX = transformObj.optDouble("translationX", 0.0).toFloat(),
                    translationY = transformObj.optDouble("translationY", 0.0).toFloat()
                )

                val adjustObj = clipObj.optJSONObject("adjustments") ?: JSONObject()
                val adjustments = ColorAdjustments(
                    brightness = adjustObj.optDouble("brightness", 0.0).toFloat(),
                    contrast = adjustObj.optDouble("contrast", 1.0).toFloat(),
                    saturation = adjustObj.optDouble("saturation", 1.0).toFloat(),
                    exposure = adjustObj.optDouble("exposure", 0.0).toFloat()
                )

                val clip = Clip(
                    id = clipObj.getString("id"),
                    filePath = clipObj.getString("filePath"),
                    sourceInMs = clipObj.getLong("sourceInMs"),
                    sourceOutMs = clipObj.getLong("sourceOutMs"),
                    timelineStartMs = clipObj.getLong("timelineStartMs"),
                    timelineEndMs = clipObj.getLong("timelineEndMs"),
                    speed = clipObj.optDouble("speed", 1.0).toFloat(),
                    transform = transform,
                    adjustments = adjustments
                )
                clipsList.add(clip)
            }
            tracksList.add(Track(id, type, clipsList, isMuted, isLocked))
        }

        return TimelineComposition(tracksList, durationMs, currentTimeMs, canvasAspectRatio)
    }
}