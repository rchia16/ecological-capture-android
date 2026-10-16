package com.rchia.ecocapture.phase0.export

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import java.io.DataOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: hash existing rows ON THE PHONE without copying/printing participant contents. */
@RunWith(AndroidJUnit4::class)
class Checkpoint3RetentionTest {
    @Test fun protectedScientificRowsSurviveUpgradeAndRegression() {
        val phase = InstrumentationRegistry.getArguments().getString("retentionPhase")
        assumeTrue("Opt-in protected-row audit", phase in listOf("before", "after"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (phase == "after") EcologicalCaptureDatabase.getInstance(context).openHelper.writableDatabase
        val result = JSONObject()
        SQLiteDatabase.openDatabase(context.getDatabasePath("ecological_capture.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for ((table, key) in listOf("clips" to "clipId", "annotations" to "annotationId", "vlm_runs" to "vlmRunId")) {
                val hash = MessageDigest.getInstance("SHA-256")
                var count = 0
                val sink = object : java.io.OutputStream() { override fun write(b: Int) {} }
                DataOutputStream(DigestOutputStream(sink, hash)).use { output ->
                    db.rawQuery("SELECT * FROM $table ORDER BY $key", null).use { cursor ->
                        output.writeInt(cursor.columnCount)
                        cursor.columnNames.forEach { name -> val bytes = name.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
                        while (cursor.moveToNext()) {
                            count++
                            for (column in 0 until cursor.columnCount) {
                                val type = cursor.getType(column)
                                output.writeInt(type)
                                when (type) {
                                    Cursor.FIELD_TYPE_INTEGER -> output.writeLong(cursor.getLong(column))
                                    Cursor.FIELD_TYPE_FLOAT -> output.writeDouble(cursor.getDouble(column))
                                    Cursor.FIELD_TYPE_STRING -> { val bytes = cursor.getString(column).toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
                                    Cursor.FIELD_TYPE_BLOB -> { val bytes = cursor.getBlob(column); output.writeInt(bytes.size); output.write(bytes) }
                                }
                            }
                        }
                    }
                }
                result.put(table, JSONObject().put("rows", count).put("sha256", hash.digest().joinToString("") { "%02x".format(it) }))
            }
            if (phase == "after") {
                assertEquals(3, db.version)
                if (InstrumentationRegistry.getArguments().getString("requireEmptyTransferTables") != "false")
                    for (table in listOf("export_snapshots", "transfer_jobs")) db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                        assertTrue(it.moveToFirst()); assertEquals("No legacy autoqueue", 0, it.getInt(0))
                    }
            }
        }
        val baselineName = InstrumentationRegistry.getArguments().getString("retentionBaseline") ?: "checkpoint3-protected-row-hashes.json"
        require(baselineName.matches(Regex("checkpoint3-protected-row-hashes(?:-[a-z0-9-]+)?\\.json")))
        val baseline = File(context.filesDir, baselineName)
        if (phase == "before") {
            assertFalse("Refuse to overwrite an audit baseline", baseline.exists())
            baseline.writeText(result.toString(), Charsets.UTF_8)
        } else {
            val before = JSONObject(baseline.readText(Charsets.UTF_8))
            for (table in listOf("clips", "annotations", "vlm_runs")) {
                assertEquals("Row count differs", before.getJSONObject(table).getInt("rows"), result.getJSONObject(table).getInt("rows"))
                assertEquals("Scientific row digest differs", before.getJSONObject(table).getString("sha256"), result.getJSONObject(table).getString("sha256"))
            }
            File(context.filesDir, "checkpoint3-protected-row-audit-result.json").writeText(result.toString(), Charsets.UTF_8)
        }
        println("Checkpoint 3 protected-row audit $phase: $result")
    }
}
