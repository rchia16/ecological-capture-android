package com.rchia.ecocapture.phase0.export

import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/** Only removes fixed UUID jobs under a trusted app-private export root. Never source recordings. */
object LocalSnapshotFiles {
    fun purge(root: File, snapshotId: String) {
        EventSnapshotSerializer.uuid(snapshotId)
        require(!Files.isSymbolicLink(root.toPath())) { "UNSAFE_EXPORT_ROOT" }
        val canonicalRoot = root.canonicalFile
        for (suffix in listOf("", ".pending")) {
            val job = File(canonicalRoot, snapshotId + suffix)
            if (!job.exists()) continue
            require(job.canonicalFile == job.absoluteFile && job.isDirectory) { "UNSAFE_EXPORT_PATH" }
            require(job.walkTopDown().all { it.canonicalFile == it.absoluteFile }) { "UNSAFE_EXPORT_PATH" }
            check(job.deleteRecursively()) { "EXPORT_PURGE_FAILED" }
        }
    }

    fun engineeringIdsForClip(root: File, clipId: String): List<String> {
        require(!Files.isSymbolicLink(root.toPath())) { "UNSAFE_EXPORT_ROOT" }
        val canonical = root.canonicalFile
        if (!canonical.exists()) return emptyList()
        return canonical.listFiles().orEmpty().filter { job ->
            if (!job.name.matches(Regex("[0-9a-f-]{36}"))) return@filter false
            require(job.canonicalFile == job.absoluteFile && job.isDirectory) { "UNSAFE_EXPORT_PATH" }
            val record = File(job, "snapshot-record.json")
            if (!record.isFile) return@filter false
            require(!Files.isSymbolicLink(record.toPath()) && record.length() <= 16 * 1024) { "UNSAFE_EXPORT_RECORD" }
            JSONObject(record.readText(Charsets.UTF_8)).getString("clip_id") == clipId
        }.map { it.name }
    }
}
