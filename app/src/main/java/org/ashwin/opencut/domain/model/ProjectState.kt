package org.ashwin.opencut.domain.model

import org.ashwin.opencut.domain.model.EffectSettings

data class ProjectState(
    val projectId: String,
    val projectName: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var effectSettings: EffectSettings = EffectSettings()
)
