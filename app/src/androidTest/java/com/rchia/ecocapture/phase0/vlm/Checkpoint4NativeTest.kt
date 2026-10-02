package com.rchia.ecocapture.phase0.vlm

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.vlm.native.NativeQwen3VlBridge
import com.rchia.ecocapture.phase0.vlm.native.NativeQwenBindings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in heavy engineering test; ordinary Room runs must not unexpectedly load a 3 GB model. */
@RunWith(AndroidJUnit4::class)
class Checkpoint4NativeTest {
    @Test fun verifyLoadUnloadTwiceAndPlayHevcAfterwards() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeCheckpoint4") == "true")
        val context = instrumentation.targetContext
        val manager = VlmModelManager(context)
        val bridge = NativeQwen3VlBridge(manager)
        val report = StringBuilder()
        fun measure(label: String) {
            val status = File("/proc/self/status").readLines().filter {
                it.startsWith("VmRSS:") || it.startsWith("VmHWM:")
            }.joinToString("; ")
            val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val line = "$label: $status; totalPssKiB=${info.totalPss}; nativeAllocatedBytes=${Debug.getNativeHeapAllocatedSize()}"
            report.appendLine(line)
            val bundle = android.os.Bundle().apply { putString("stream", "$line\n") }
            instrumentation.sendStatus(0, bundle)
        }
        val reportFile = File(context.filesDir, "checkpoint4-native-report.txt")
        try {
            measure("before-verification")
            val started = SystemClock.elapsedRealtime()
            val inspection = manager.inspect()
            assertEquals(VlmModelStatus.READY, inspection.status)
            report.appendLine("verificationMs=${SystemClock.elapsedRealtime() - started}")
            assertEquals(NativeQwen3VlBridge.EXPECTED_RUNTIME, bridge.runtimeInfo())
            // Known missing paths exercise native partial-load cleanup without corrupting real models.
            assertEquals(1, NativeQwenBindings.nativeLoadModel("/missing/language.gguf", "/missing/projector.gguf"))
            assertEquals(2, NativeQwenBindings.nativeLoadModel(
                requireNotNull(inspection.bundle).language.file.absolutePath, "/missing/projector.gguf"))
            assertEquals(0, NativeQwenBindings.nativeUnload())
            measure("after-partial-failure-cleanup")
            repeat(2) { index ->
                val loadStart = SystemClock.elapsedRealtime()
                try {
                    assertEquals(VlmLoadResult.Ready, bridge.load())
                    report.appendLine("cycle${index + 1}VerificationAndLoadMs=${SystemClock.elapsedRealtime() - loadStart}")
                    measure("cycle${index + 1}-loaded")
                    assertEquals(VlmLoadResult.Failure(VlmFailure.NativeFailure(7)), bridge.load())
                } finally {
                    assertEquals(VlmLoadResult.Ready, bridge.unload())
                }
                report.appendLine("cycle${index + 1}CompleteMs=${SystemClock.elapsedRealtime() - loadStart}")
                measure("cycle${index + 1}-unloaded")
            }
            assertEquals(VlmLoadResult.Ready, bridge.unload()) // Idempotent cleanup.

            // A copy of a finalized glasses HEVC fixture; never operate on participant clip paths.
            val fixture = File(context.cacheDir, "checkpoint4-playback.mp4")
            assertTrue("Provision the isolated HEVC playback fixture", fixture.isFile && fixture.length() > 0)
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(fixture.absolutePath)
                assertTrue((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == "video/hevc"
                })
            } finally { extractor.release() }
            val rendered = CountDownLatch(1)
            var playbackError: PlaybackException? = null
            lateinit var player: ExoPlayer
            lateinit var texture: SurfaceTexture
            lateinit var surface: Surface
            instrumentation.runOnMainSync {
                texture = SurfaceTexture(false)
                surface = Surface(texture)
                player = ExoPlayer.Builder(context).build()
                player.setVideoSurface(surface)
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { rendered.countDown() }
                    override fun onPlayerError(error: PlaybackException) { playbackError = error; rendered.countDown() }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                player.prepare()
                player.play()
            }
            try {
                assertTrue("HEVC frame was not rendered after native unload", rendered.await(20, TimeUnit.SECONDS))
                assertNull(playbackError)
                report.appendLine("postUnloadMedia3HevcFirstFrame=PASS")
            } finally {
                instrumentation.runOnMainSync { player.release(); surface.release(); texture.release() }
            }
            measure("after-playback")
        } finally {
            bridge.unload()
            reportFile.writeText(report.toString())
        }
    }
}
