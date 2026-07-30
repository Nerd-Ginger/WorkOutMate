package com.nerdginger.workoutmate

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * File I/O for the "Save progress" feature.
 *
 * An `<a download>` does nothing inside a WebView, so every export and import
 * is routed through the Storage Access Framework instead. That also means the
 * app needs no storage permission at all — the user picks the destination and
 * the system grants access to that one document.
 */
object BackupIo {

    private const val SNAPSHOT_DIR = "autobackup"
    private const val SNAPSHOT_PREFIX = "auto-"
    private const val SNAPSHOT_SUFFIX = ".json"

    /** Snapshots are a corruption safety net, not an archive — keep the last 10. */
    private const val MAX_SNAPSHOTS = 10

    /** Guards against a malformed page handing us something enormous to import. */
    private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    fun writeText(context: Context, uri: Uri, body: String): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")
            ?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not open the selected file for writing")
    }

    fun readText(context: Context, uri: Uri): Result<String> = runCatching {
        val size = sizeOf(context, uri)
        require(size == null || size <= MAX_IMPORT_BYTES) {
            "That file is too large to be a WorkOutMate backup"
        }
        context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open the selected file for reading")
    }

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun sizeOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    }.getOrNull()

    /**
     * Writes a rolling snapshot into app-internal storage. This survives WebView
     * storage being wiped or corrupted, but not an uninstall — it complements
     * user-driven exports rather than replacing them.
     */
    fun writeSnapshot(context: Context, stamp: String, body: String): Result<String> = runCatching {
        val dir = File(context.filesDir, SNAPSHOT_DIR).apply { mkdirs() }
        val safeStamp = stamp.replace(Regex("[^A-Za-z0-9_-]"), "-").take(40)
        val file = File(dir, "$SNAPSHOT_PREFIX$safeStamp$SNAPSHOT_SUFFIX")
        file.writeText(body, Charsets.UTF_8)
        prune(dir)
        file.name
    }

    fun listSnapshots(context: Context): List<String> {
        val dir = File(context.filesDir, SNAPSHOT_DIR)
        if (!dir.isDirectory) return emptyList()
        return snapshotFiles(dir).sortedByDescending { it.lastModified() }.map { it.name }
    }

    fun readSnapshot(context: Context, name: String): Result<String> = runCatching {
        // Resolve and re-check the parent so a crafted name cannot escape the
        // snapshot directory via `..` segments.
        val dir = File(context.filesDir, SNAPSHOT_DIR)
        val file = File(dir, name).canonicalFile
        require(file.parentFile == dir.canonicalFile) { "Unknown snapshot" }
        require(file.isFile) { "Snapshot not found" }
        file.readText(Charsets.UTF_8)
    }

    private fun prune(dir: File) {
        val files = snapshotFiles(dir).sortedByDescending { it.lastModified() }
        files.drop(MAX_SNAPSHOTS).forEach { it.delete() }
    }

    private fun snapshotFiles(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name.startsWith(SNAPSHOT_PREFIX) && f.name.endsWith(SNAPSHOT_SUFFIX)
        }?.toList().orEmpty()
}
