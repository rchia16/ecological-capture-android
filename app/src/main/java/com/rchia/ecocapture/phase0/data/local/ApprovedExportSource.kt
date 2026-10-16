package com.rchia.ecocapture.phase0.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class ApprovedExportSource(
    @Embedded val clip: ClipEntity,
    @Relation(parentColumn = "clipId", entityColumn = "clipId") val annotations: List<AnnotationEntity>,
    @Relation(parentColumn = "clipId", entityColumn = "clipId") val vlmRuns: List<VlmRunEntity>,
)
