package com.rchia.ecocapture.phase0.vlm

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VlmFrameSamplerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val files = mutableListOf<File>()
    private fun file(bytes: ByteArray = byteArrayOf(1)) = File(instrumentation.targetContext.cacheDir,
        "sampler-${UUID.randomUUID()}.mp4").also { it.writeBytes(bytes); files += it }
    private fun fixture(name: String): File = file().also { target ->
        instrumentation.context.assets.open("checkpoint5/$name.mp4").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }
    private fun success(result: FrameSamplingResult) = (result as FrameSamplingResult.Success).samples
    @After fun cleanup() { files.forEach { it.delete() } }

    @Test fun normalSixtySecondHevcClipHasOrderedDeterministicFrames() = runBlocking {
        val source = fixture("normal60")
        val original = source.readBytes()
        success(VlmFrameSampler().sample(source)).use { first ->
            assertTrue(first.durationMs in 59000..61000)
            assertEquals(5, first.frames.size)
            assertEquals(FrameSamplingConfig().timestampsMs(first.durationMs), first.frames.map { it.requestedTimestampMs })
            success(VlmFrameSampler().sample(source)).use { second ->
                first.frames.zip(second.frames).forEach { (a, b) -> assertTrue(a.bitmap.sameAs(b.bitmap)) }
            }
            val provenance = JSONObject(first.provenanceJson())
            assertEquals(5, provenance.getJSONArray("frames").length())
            first.frames.forEach { assertNull(it.actualTimestampMs); assertEquals(320, it.inferenceWidth); assertEquals(180, it.inferenceHeight) }
            assertTrue(provenance.getJSONArray("frames").getJSONObject(0).isNull("actualTimestampMs"))
        }
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun shortHevcClipIsSampledWithoutUpscaling() = runBlocking {
        success(VlmFrameSampler().sample(fixture("short"))).use { batch ->
            assertEquals(5, batch.frames.size)
            assertTrue(batch.durationMs in 1..2000)
            batch.frames.forEach { assertEquals(320, it.inferenceWidth); assertEquals(180, it.inferenceHeight) }
        }
    }
    @Test fun portraitHevcKeepsAspectRatioAndCanBeDownscaled() = runBlocking {
        success(VlmFrameSampler().sample(fixture("portrait"), FrameSamplingConfig(maxLongEdge = 96))).use { batch ->
            batch.frames.forEach {
                assertEquals(54, it.inferenceWidth); assertEquals(96, it.inferenceHeight)
                assertEquals(108, it.sourceWidth); assertEquals(192, it.sourceHeight)
            }
        }
    }
    @Test fun containerRotationIsAppliedOnce() = runBlocking {
        success(VlmFrameSampler().sample(fixture("rotated"))).use { batch ->
            batch.frames.forEach {
                assertTrue(it.sourceRotationDegrees == 90 || it.sourceRotationDegrees == 270)
                assertEquals(180, it.inferenceWidth); assertEquals(320, it.inferenceHeight)
            }
            success(VlmFrameSampler().sample(fixture("short"))).use { original ->
                val expected = Bitmap.createBitmap(original.frames[0].bitmap, 0, 0, 320, 180,
                    android.graphics.Matrix().apply { postRotate(batch.frames[0].sourceRotationDegrees.toFloat()) }, false)
                try { assertTrue("Container rotation must preserve pixel orientation", expected.sameAs(batch.frames[0].bitmap)) }
                finally { expected.recycle() }
            }
        }
    }
    @Test fun missingEmptyAndCorruptVideoReturnTypedFailures() = runBlocking {
        val missing = file().also { it.delete() }
        assertEquals(FrameSamplingResult.Failure(FrameSamplingFailure.MISSING_VIDEO), VlmFrameSampler().sample(missing))
        assertEquals(FrameSamplingResult.Failure(FrameSamplingFailure.INVALID_VIDEO), VlmFrameSampler().sample(file(byteArrayOf())))
        assertEquals(FrameSamplingResult.Failure(FrameSamplingFailure.INVALID_VIDEO), VlmFrameSampler().sample(file("corrupt MP4".toByteArray())))
    }

    private class Decoder(private val meta: FrameVideoMetadata,
        private val obtain: (Int) -> Bitmap?) : SavedVideoDecoder {
        var closed = false
        private var count = 0
        override fun open(file: File) { }
        override fun metadata() = meta
        override fun frame(timestampMs: Long, bound: Int) = obtain(count++)
        override fun close() { closed = true }
    }
    private val metadata = FrameVideoMetadata(30000, 16, 8, 0)
    private fun bitmap() = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)

    @Test fun zeroUnknownAndNegativeDurationReleaseDecoder() = runBlocking {
        listOf(null, 0L, -1L).forEach { duration ->
            val decoder = Decoder(metadata.copy(durationMs = duration)) { fail("Must not decode invalid duration"); null }
            assertEquals(FrameSamplingResult.Failure(FrameSamplingFailure.INVALID_DURATION), VlmFrameSampler { decoder }.sample(file()))
            assertTrue(decoder.closed)
        }
    }
    @Test fun successfulBatchOwnsAndRecyclesAllBitmapsIdempotently() = runBlocking {
        val decoder = Decoder(metadata) { bitmap() }
        val batch = success(VlmFrameSampler { decoder }.sample(file()))
        assertTrue(decoder.closed)
        assertTrue(batch.frames.none { it.bitmap.isRecycled })
        batch.close(); batch.close()
        assertTrue(batch.frames.all { it.bitmap.isRecycled })
    }
    @Test fun partialExtractionFailureRecyclesEarlierFrames() = runBlocking {
        val first = bitmap()
        val decoder = Decoder(metadata) { if (it == 0) first else null }
        assertEquals(FrameSamplingResult.Failure(FrameSamplingFailure.FRAME_UNAVAILABLE), VlmFrameSampler { decoder }.sample(file()))
        assertTrue(first.isRecycled); assertTrue(decoder.closed)
    }
    @Test fun cancellationRecyclesPartialFramesAndPropagates() = runBlocking {
        val first = bitmap()
        val decoder = Decoder(metadata) { if (it == 0) first else throw CancellationException("test cancellation") }
        try { VlmFrameSampler { decoder }.sample(file()); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertTrue(first.isRecycled); assertTrue(decoder.closed)
    }
    @Test fun allocationFailureRecyclesPartialFrames() = runBlocking {
        val first = bitmap()
        val decoder = Decoder(metadata) { if (it == 0) first else throw OutOfMemoryError("test allocation failure") }
        assertEquals(FrameSamplingResult.Failure(FrameSamplingFailure.OUT_OF_MEMORY), VlmFrameSampler { decoder }.sample(file()))
        assertTrue(first.isRecycled); assertTrue(decoder.closed)
    }
    @Test fun cancellingJobDuringBlockingDecodeRecyclesReturnedFrame() = runBlocking {
        val first = bitmap()
        val entered = java.util.concurrent.CountDownLatch(1)
        val finishDecode = java.util.concurrent.CountDownLatch(1)
        val decoder = Decoder(metadata) {
            entered.countDown()
            check(finishDecode.await(10, java.util.concurrent.TimeUnit.SECONDS))
            first
        }
        val source = file()
        val job = launch(Dispatchers.Default) { VlmFrameSampler { decoder }.sample(source) }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            finishDecode.countDown()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(first.isRecycled)
            assertTrue(decoder.closed)
        } finally { finishDecode.countDown(); job.cancelAndJoin(); if (!first.isRecycled) first.recycle() }
    }
    @Test fun invalidGeometryRecyclesTheReturnedBitmap() = runBlocking {
        val wrong = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888)
        val decoder = Decoder(metadata) { wrong }
        assertEquals(FrameSamplingResult.Failure(FrameSamplingFailure.FRAME_UNAVAILABLE), VlmFrameSampler { decoder }.sample(file()))
        assertTrue(wrong.isRecycled); assertTrue(decoder.closed)
    }
}
