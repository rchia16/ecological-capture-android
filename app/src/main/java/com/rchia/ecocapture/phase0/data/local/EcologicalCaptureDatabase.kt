package com.rchia.ecocapture.phase0.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipEntity::class], version = 1, exportSchema = false)
abstract class EcologicalCaptureDatabase : RoomDatabase() {
    abstract fun clipDao(): ClipDao

    companion object {
        @Volatile private var instance: EcologicalCaptureDatabase? = null

        fun getInstance(context: Context): EcologicalCaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EcologicalCaptureDatabase::class.java,
                    "ecological_capture.db",
                ).build().also { instance = it }
            }
    }
}
