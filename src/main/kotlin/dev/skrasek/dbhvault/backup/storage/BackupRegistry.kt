package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.backup.BackupMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.name

/** One backup file on disk: its filesystem path, parsed metadata, and size. */
data class BackupEntry(
    val path: Path,
    val metadata: BackupMetadata,
    val sizeBytes: Long,
)

/**
 * Scans [backupDir] for backup archive files, parses their filenames into
 * [BackupMetadata], and returns them as [BackupEntry] objects (newest first).
 *
 * Top-level only — subdirectories are not recursed. Files whose names don't
 * parse via [BackupMetadata.parse] (README.md, .DS_Store, etc.) are silently
 * skipped. If [backupDir] doesn't exist or isn't a directory, returns an
 * empty list rather than throwing — a misconfigured path shouldn't crash
 * the server, and a fresh install has no backups yet.
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
