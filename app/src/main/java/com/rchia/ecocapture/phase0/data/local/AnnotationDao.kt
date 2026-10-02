package com.rchia.ecocapture.phase0.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(annotation: AnnotationEntity)

    @Query("SELECT * FROM annotations WHERE clipId = :clipId AND isCurrent = 1 LIMIT 1")
    suspend fun current(clipId: String): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE clipId = :clipId AND isCurrent = 1 LIMIT 1")
    fun observeCurrent(clipId: String): Flow<AnnotationEntity?>

    @Query("UPDATE annotations SET isCurrent = 0 WHERE annotationId = :id")
    suspend fun supersede(id: String)

    @Query("SELECT * FROM annotations WHERE clipId = :clipId ORDER BY createdAtEpochMs, annotationId")
    suspend fun history(clipId: String): List<AnnotationEntity>
}
