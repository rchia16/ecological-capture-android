package com.rchia.ecocapture.phase0.export

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.room.withTransaction
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import java.io.File
import java.nio.ByteBuffer

class RoomEventSnapshotStore(private val database: EcologicalCaptureDatabase) : EventSnapshotStore {
    private suspend fun readRows(clipId: String) = EventSnapshotSource(
        checkNotNull(database.clipDao().getClip(clipId)) { "CLIP_NOT_FOUND" },
        database.annotationDao().history(clipId), database.vlmRunDao().history(clipId),
    )

    override suspend fun read(clipId: String) = database.withTransaction { readRows(clipId) }

    override suspend fun publishIfUnchanged(expected: EventSnapshotSource, publish: () -> Unit) {
        database.withTransaction {
            check(readRows(expected.clip.clipId) == expected) { "SNAPSHOT_SOURCE_CHANGED_RETRY" }
            publish()
        }
    }

    companion object {
        fun validateVideo(file: File) {
            ApprovedEventSnapshotBuilder.validateMp4Boxes(file)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val video = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                require(video != null) { "INVALID_MP4_VIDEO" }
                val format = extractor.getTrackFormat(video)
                require(format.getInteger(MediaFormat.KEY_WIDTH) > 0 && format.getInteger(MediaFormat.KEY_HEIGHT) > 0)
                extractor.selectTrack(video)
                val sampleSize = extractor.sampleSize
                require(sampleSize in 1..(16L * 1024 * 1024)) { "INVALID_MP4_VIDEO" }
                require(extractor.readSampleData(ByteBuffer.allocate(sampleSize.toInt()), 0) == sampleSize.toInt()) {
                    "INVALID_MP4_VIDEO"
                }
            } finally { extractor.release() }
        }
    }
}
