package com.rchia.ecocapture.phase0.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey val annotationId: String,
    val clipId: String,
    val source: String,
    val text: String,
    val createdAtEpochMs: Long,
    val parentVlmRunId: String?,
    val supersedesAnnotationId: String?,
    val isCurrent: Boolean,
)
