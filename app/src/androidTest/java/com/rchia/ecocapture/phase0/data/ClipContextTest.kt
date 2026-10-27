package com.rchia.ecocapture.phase0.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipContextTest {
    @Test fun manualCorrectionWinsAndDeletionErasesContext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "clip_context_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build()
        try {
            db.clipDao().insert(ClipEntity("clip", "/synthetic/clip.mp4", null, 1000, 1000,
                640, 480, 7, "UNREVIEWED", "UNDECIDED", "test", 1000))
            val dao = db.clipContextDao()
            dao.insertRecordingZone("clip", "Australia/Sydney", 1000)
            dao.setManualSuburb("clip", "Ultimo", "MANUAL", 1001)
            dao.setAutomaticSuburb("clip", "Haymarket", 1002)
            assertEquals("Ultimo", dao.observe("clip").first()?.suburb)
            db.clipDao().markDeletedAndEraseText("clip", 1003)
            assertNull(dao.observe("clip").first())
            dao.insertRecordingZone("clip", "Australia/Sydney", 1004)
            dao.setAutomaticSuburb("clip", "Haymarket", 1005)
            assertNull(dao.observe("clip").first())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
