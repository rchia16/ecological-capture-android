package com.rchia.ecocapture.phase0.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ClipEntity::class, VlmRunEntity::class, AnnotationEntity::class], version = 2, exportSchema = false)
abstract class EcologicalCaptureDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao
    abstract fun vlmRunDao(): VlmRunDao
    abstract fun annotationDao(): AnnotationDao

    companion object {
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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
