package com.rchia.ecocapture.phase0.export

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.local.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseMigrationTest {
    @Test fun versionThreeJobsRemainSimulationAndAreNotEnqueued(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "firebase_migration_${UUID.randomUUID()}.db"
        val id = UUID.randomUUID().toString()
        val original = TransferJobEntity(id,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
            "SIMULATED_COMPLETE",1000,2000,0,100,null,"SIMULATION","hash",null,"test")
        val initial = Room.databaseBuilder(context,EcologicalCaptureDatabase::class.java,name).build()
        try {
            initial.transferJobDao().insert(original)
            val sql = initial.openHelper.writableDatabase
            sql.execSQL("ALTER TABLE transfer_jobs RENAME TO transfer_jobs_v4")
            sql.execSQL("DROP INDEX index_transfer_jobs_clipId")
            sql.execSQL("DROP INDEX index_transfer_jobs_snapshotId")
            EcologicalCaptureDatabase.MIGRATION_2_3.migrate(sql)
            val columns = "jobId,snapshotId,clipId,state,createdAtEpochMs,updatedAtEpochMs,retryCount,progressPercent,lastSafeErrorCode,transportMode,sourceVersionSha256,previousSnapshotId,appVersion"
            sql.execSQL("INSERT INTO transfer_jobs($columns) SELECT $columns FROM transfer_jobs_v4")
            sql.execSQL("DROP TABLE transfer_jobs_v4")
            sql.execSQL("DELETE FROM room_master_table")
            sql.version = 3
        } finally { initial.close() }
        val migrated = Room.databaseBuilder(context,EcologicalCaptureDatabase::class.java,name)
            .addMigrations(EcologicalCaptureDatabase.MIGRATION_3_4).build()
        try {
            assertEquals(original,migrated.transferJobDao().get(id))
            assertEquals(4,migrated.openHelper.writableDatabase.version)
            assertNull(migrated.transferJobDao().get(id)!!.remoteReceiptJson)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
