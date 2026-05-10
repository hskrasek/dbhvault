package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.backup.BackupMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * One backup file on disk: its filesystem path, parsed metadata, and size.
 *
 * Fully implemented — only [BackupRegistry] is stubbed.
 */
data class BackupEntry(
    val path: Path,
    val metadata: BackupMetadata,
    val sizeBytes: Long,
)

/**
 * Scans [backupDir] for backup archive files, parses their filenames into
 * [BackupMetadata], and returns them as [BackupEntry] objects.
 *
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/backup/storage/BackupRegistryTest.kt`
 *
 * Behaviors:
 *  - [list] scans only the **top-level** of [backupDir] — subdirectories are
 *    not recursed into. Each top-level regular file whose name parses via
 *    [BackupMetadata.parse] becomes a [BackupEntry]; non-matching files are
 *    silently skipped (so `README.md`, `.DS_Store`, etc. don't error).
 *  - Returned list is sorted **newest-first** by `metadata.timestamp`.
 *  - `sizeBytes` comes from `Files.size(path)`.
 *  - If [backupDir] does not exist, is not a directory, or is empty, returns
 *    an empty list rather than throwing — a misconfigured path shouldn't
 *    crash the server, and a fresh install has no backups yet.
 *  - [mostRecent] returns the first element of [list] or null if empty.
 *
 * Suggested approach: `Files.list(backupDir)` filtered to regular files,
 * map to [BackupEntry] via parse, drop nulls, sort by descending timestamp.
 */
class BackupRegistry(private val backupDir: Path) {
    fun list(): List<BackupEntry> {
        if (!Files.isDirectory(backupDir)) return emptyList()

        return Files.list(backupDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { path ->
                    val meta = BackupMetadata.parse(path.name) ?: return@map null
                    BackupEntry(path, meta, path.fileSize())
                }
                .filter { it != null }
                .map { it!! }
                .sorted(compareByDescending { it.metadata.timestamp })
                .toList()
        }
    }

    fun mostRecent(): BackupEntry? = list().firstOrNull()
}
