package com.rchia.ecocapture.phase0.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate disposable database, never the participant database or recording files. */
@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: EcologicalCaptureDatabase
    private val databaseName = "phase1_regression_${UUID.randomUUID()}.db"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        openDatabase()
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, databaseName).build()
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun insertReadAndBothStateUpdatesSurviveReopen() = runBlocking {
        database.clipDao().insert(clip("a"))
        database.clipDao().updateReviewState("a", "DEFERRED", 2000)
        reopenDatabase()
        assertEquals("DEFERRED", database.clipDao().getClip("a")?.reviewState)
        database.clipDao().updateApprovalState("a", "WITHHELD", 3000)
        reopenDatabase()
        assertEquals("WITHHELD", database.clipDao().getClip("a")?.approvalState)
    }

    @Test fun decisionsAndTombstonePersistAndFilterTheQueue() = runBlocking {
        val dao = database.clipDao()
        dao.insert(clip("approve"))
        dao.insert(clip("delete"))
        assertEquals(1, dao.deferClip("approve", 2000))
        reopenDatabase()
        assertEquals("DEFERRED", database.clipDao().observeClip("approve").first()?.reviewState)
        assertEquals(1, database.clipDao().approveClip("approve", 3000))
        assertEquals(1, database.clipDao().markDeleted("delete", 3000))
        reopenDatabase()
        assertTrue(database.clipDao().observeReviewQueue().first().isEmpty())
        assertEquals("APPROVED", database.clipDao().getClip("approve")?.approvalState)
        assertEquals("REVIEWED", database.clipDao().getClip("approve")?.reviewState)
        assertEquals("DELETED", database.clipDao().getClip("delete")?.approvalState)
        assertEquals(0, database.clipDao().deferClip("delete", 4000))
        assertEquals(0, database.clipDao().approveClip("delete", 4000))
    }

    @Test fun repeatedMediaInsertionKeepsOriginalIdentityAndDecisions() = runBlocking {
        val first = clip("original")
        database.clipDao().insertIfVideoAbsent(first)
        database.clipDao().approveClip(first.clipId, 2000)
        database.clipDao().insertIfVideoAbsent(first.copy(clipId = "duplicate"))
        reopenDatabase()
        assertNull(database.clipDao().getClip("duplicate"))
        assertEquals("APPROVED", database.clipDao().getClip("original")?.approvalState)
    }

    private fun clip(id: String) = ClipEntity(
        clipId = id,
        videoPath = "${context.filesDir}/phase1-test-$id.mp4",
        metadataPath = null,
        createdAtEpochMs = 1000,
        durationMs = 2000,
        width = 720,
        height = 1280,
        sampleCount = 14,
        reviewState = "UNREVIEWED",
        approvalState = "UNDECIDED",
        createdByAppVersion = "test",
        updatedAtEpochMs = 1000,
    )
}
