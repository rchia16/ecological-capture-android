package com.rchia.ecocapture.phase0.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ClipEntity::class, VlmRunEntity::class, AnnotationEntity::class,
    ExportSnapshotEntity::class, TransferJobEntity::class, ClipContextEntity::class], version = 5, exportSchema = false)
abstract class EcologicalCaptureDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun vlmRunDao(): VlmRunDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun exportSnapshotDao(): ExportSnapshotDao
    abstract fun transferJobDao(): TransferJobDao
    abstract fun clipContextDao(): ClipContextDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS clip_contexts (
                    clipId TEXT NOT NULL PRIMARY KEY, recordingTimeZoneId TEXT,
                    suburb TEXT, locationSource TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL
                )""".trimIndent())
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("encryptedArtifactPath", "ciphertextSha256", "encryptionKeyVersions", "remoteReceiptJson"))
                    db.execSQL("ALTER TABLE transfer_jobs ADD COLUMN $column TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS export_snapshots (
                    snapshotId TEXT NOT NULL PRIMARY KEY, clipId TEXT NOT NULL, previousSnapshotId TEXT,
                    createdAtEpochMs INTEGER NOT NULL, eventJsonSha256 TEXT NOT NULL,
                    manifestSha256 TEXT NOT NULL, mp4Sha256 TEXT NOT NULL, snapshotState TEXT NOT NULL,
                    appVersion TEXT NOT NULL, studyParticipantCode TEXT
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_export_snapshots_clipId ON export_snapshots(clipId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS transfer_jobs (
                    jobId TEXT NOT NULL PRIMARY KEY, snapshotId TEXT NOT NULL, clipId TEXT NOT NULL,
                    state TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL,
                    retryCount INTEGER NOT NULL, progressPercent INTEGER NOT NULL,
                    lastSafeErrorCode TEXT, transportMode TEXT NOT NULL, sourceVersionSha256 TEXT NOT NULL,
                    previousSnapshotId TEXT, appVersion TEXT NOT NULL
                )""".trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_jobs_clipId ON transfer_jobs(clipId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transfer_jobs_snapshotId ON transfer_jobs(snapshotId)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vlm_runs (
                        vlmRunId TEXT NOT NULL PRIMARY KEY, clipId TEXT NOT NULL,
                        modelId TEXT NOT NULL, modelQuant TEXT NOT NULL,
                        languageModelSha256 TEXT NOT NULL, mmprojSha256 TEXT NOT NULL,
                        runtimeName TEXT NOT NULL, runtimeCommit TEXT NOT NULL, promptVersion TEXT NOT NULL,
                        generatedAtEpochMs INTEGER NOT NULL, firstPresentedAtEpochMs INTEGER,
                        inferenceDurationMs INTEGER NOT NULL, frameSamplingJson TEXT NOT NULL,
                        generationConfigJson TEXT NOT NULL, rawOutput TEXT NOT NULL,
                        description TEXT NOT NULL, disposition TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS annotations (
                        annotationId TEXT NOT NULL PRIMARY KEY, clipId TEXT NOT NULL,
                        source TEXT NOT NULL, text TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL,
                        parentVlmRunId TEXT, supersedesAnnotationId TEXT, isCurrent INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        @Volatile private var instance: EcologicalCaptureDatabase? = null

        fun getInstance(context: Context): EcologicalCaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EcologicalCaptureDatabase::class.java,
                    "ecological_capture.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}
