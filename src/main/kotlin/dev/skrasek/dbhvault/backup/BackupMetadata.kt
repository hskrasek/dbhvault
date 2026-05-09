package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import java.time.Instant

/**
 * Parsed metadata for a single backup archive on disk.
 *
 * The data class itself is fully implemented — only [Companion.parse] is
 * stubbed. Test contract: `src/test/kotlin/dev/skrasek/dbhvault/backup/BackupMetadataTest.kt`.
 *
 * `parse` must be the inverse of [BackupNaming.fileName] for valid inputs:
 *
 *  - `world-<ts>.<ext>`              -> scheduled
 *  - `world-<ts>--<name>.<ext>`      -> pinned
 *
 * Where:
 *  - `<ts>` is `yyyy-MM-dd'T'HH-mm-ss'Z'`
 *  - `<name>` is a non-empty string of `[A-Za-z0-9_-]` (no spaces, no slashes,
 *    no consecutive empty segments)
 *  - `<ext>` is `tar.zst` or `zip`
 *
 * `parse` MUST return `null` (never throw) for any malformed input — that
 * includes:
 *
 *  - Files in the backup directory that aren't ours (`README.md`, `.DS_Store`)
 *  - Wrong prefix or wrong-case prefix (`World-...` is not `world-...`)
 *  - Timestamps with the wrong separators (e.g. colons) or impossible date
 *    values (month=13, day=32, hour=25)
 *  - Unsupported extensions
 *  - Empty filename
 *  - Names containing whitespace, path separators, or empty `--` between
 *    timestamp and extension
 *
 * The "throws nothing on bad input" requirement matters because `parse` is
 * called on every file in the backup directory during `BackupRegistry.list()`
 * — a single rogue file must not prevent listing the rest.
 */
data class BackupMetadata(
    val timestamp: Instant,
    val name: String?,
    val format: ArchiveFormat,
) {
    val isPinned: Boolean get() = name != null

    companion object {
        fun parse(fileName: String): BackupMetadata? =
            TODO("Implement: regex-match world-<ts>(--<name>)?.<ext>; safely parse <ts>; return null on any failure")
    }
}
