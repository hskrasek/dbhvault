package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parsed metadata for a single backup archive on disk.
 *
 * [Companion.parse] is the inverse of [BackupNaming.fileName] for valid inputs:
 *   - `world-<ts>.<ext>`           → scheduled
 *   - `world-<ts>--<name>.<ext>`   → pinned
 *
 * Where `<ts>` is `yyyy-MM-dd'T'HH-mm-ss'Z'`, `<name>` is `[A-Za-z0-9_-]+`,
 * `<ext>` is `tar.zst` or `zip`.
 *
 * [Companion.parse] MUST return null (never throw) for any malformed input —
 * it is called on every file in the backup directory during
 * [BackupRegistry.list], and a single rogue file (README.md, .DS_Store,
 * hand-edited junk) must not prevent listing the rest.
 */
data class BackupMetadata(
    val timestamp: Instant,
    val name: String?,
    val format: ArchiveFormat,
) {
    val isPinned: Boolean get() = name != null

    companion object {
        private val PATTERN = Regex(
            """^world-(\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z)(?:--([A-Za-z0-9_-]+))?\.(tar\.zst|zip)$"""
        )

        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC)

        fun parse(fileName: String): BackupMetadata? {
            val match = PATTERN.matchEntire(fileName) ?: return null
            val (tsStr, name, ext) = match.destructured

            val instant = try {
                Instant.from(TIMESTAMP_FORMAT.parse(tsStr))
            } catch (e: Exception) {
                return null
            }

            val format = when (ext) {
                "tar.zst" -> ArchiveFormat.TAR_ZST
                "zip" -> ArchiveFormat.ZIP
                else -> return null
            }

            return BackupMetadata(instant, name.ifEmpty { null }, format)
        }
    }
}
