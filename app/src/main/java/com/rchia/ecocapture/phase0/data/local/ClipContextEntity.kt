package com.rchia.ecocapture.phase0.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Approximate recording context stays on the phone, outside export snapshots. */
@Entity(tableName = "clip_contexts")
data class ClipContextEntity(
    @PrimaryKey val clipId: String,
    val recordingTimeZoneId: String?,
    val suburb: String?,
    val locationSource: String,
    val updatedAtEpochMs: Long,
)

@Dao
interface ClipContextDao {
    @Query("SELECT * FROM clip_contexts WHERE clipId = :clipId")
    fun observe(clipId: String): Flow<ClipContextEntity?>

    @Query("""INSERT OR IGNORE INTO clip_contexts
        (clipId, recordingTimeZoneId, suburb, locationSource, updatedAtEpochMs)
        SELECT clipId, :zone, NULL, 'UNAVAILABLE', :time FROM clips
        WHERE clipId = :clipId AND approvalState != 'DELETED'""")
    suspend fun insertRecordingZone(clipId: String, zone: String, time: Long)

    @Query("""UPDATE clip_contexts SET recordingTimeZoneId = :zone WHERE clipId = :clipId
        AND recordingTimeZoneId IS NULL AND EXISTS
        (SELECT 1 FROM clips WHERE clipId = :clipId AND approvalState != 'DELETED')""")
    suspend fun fillRecordingZone(clipId: String, zone: String)

    @Query("""UPDATE clip_contexts SET suburb = :suburb, locationSource = 'AUTOMATIC',
        updatedAtEpochMs = :time WHERE clipId = :clipId AND locationSource = 'UNAVAILABLE'
        AND EXISTS (SELECT 1 FROM clips WHERE clipId = :clipId AND approvalState != 'DELETED')""")
    suspend fun setAutomaticSuburb(clipId: String, suburb: String, time: Long)

    @Query("""INSERT OR IGNORE INTO clip_contexts
        (clipId, recordingTimeZoneId, suburb, locationSource, updatedAtEpochMs)
        SELECT clipId, NULL, NULL, 'UNAVAILABLE', :time FROM clips
        WHERE clipId = :clipId AND approvalState != 'DELETED'""")
    suspend fun insertLegacyContext(clipId: String, time: Long)

    @Query("""UPDATE clip_contexts SET suburb = :suburb, locationSource = :source,
        updatedAtEpochMs = :time WHERE clipId = :clipId
        AND EXISTS (SELECT 1 FROM clips WHERE clipId = :clipId AND approvalState != 'DELETED')""")
    suspend fun setManualSuburb(clipId: String, suburb: String?, source: String, time: Long)

    @Query("DELETE FROM clip_contexts WHERE clipId = :clipId")
    suspend fun delete(clipId: String)
}
