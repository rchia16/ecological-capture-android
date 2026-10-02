package com.rchia.ecocapture.phase0.vlm

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in engineering output only. Reads saved recordings and writes disposable cache images. */
@RunWith(AndroidJUnit4::class)
class Checkpoint5InspectionTest {
    @Test fun exportThreeGlassesClipTimelines() = runBlocking {
        val names = InstrumentationRegistry.getArguments().getString("checkpoint5RecordingNames")
        assumeTrue(names != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filenames = requireNotNull(names).split(',')
        require(filenames.size >= 3 && filenames.distinct().size == filenames.size)
        require(filenames.all { it.matches(Regex("clip_[A-Za-z0-9_]+\\.mp4")) })
        val destination = File(context.cacheDir, "checkpoint5-inspection").apply { mkdirs() }
        val clips = JSONArray()
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        filenames.forEachIndexed { clipIndex, name ->
            val source = File(context.filesDir, "recordings/$name")
            val originalHash = sha(source)
            val result = VlmFrameSampler().sample(source)
            assertTrue("Failed to sample $name: $result", result is FrameSamplingResult.Success)
            (result as FrameSamplingResult.Success).samples.use { batch ->
                val images = JSONArray()
                batch.frames.forEach { frame ->
                    val imageName = "clip${clipIndex + 1}-frame${frame.index + 1}.jpg"
                    File(destination, imageName).outputStream().use { output ->
                        assertTrue(frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                    }
                    images.put(imageName)
                }
                clips.put(JSONObject().apply {
                    put("sourceFilename", name); put("sourceSha256", originalHash)
                    put("sampling", JSONObject(batch.provenanceJson())); put("images", images)
                })
            }
            assertEquals("Source recording must remain byte-identical", originalHash, sha(source))
        }
        File(destination, "manifest.json").writeText(JSONObject().put("clips", clips).toString(2))
    }
}
