package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Generates archive filenames from a timestamp, optional name, and format.
 *
 * Format: `world-<UTC-timestamp>(--<sanitized-name>)?.<ext>` where the
 * timestamp is `yyyy-MM-dd'T'HH-mm-ss'Z'` (dashes for time separators
 * because colons aren't filesystem-safe on Windows). Sub-second precision
 * is dropped.
 *
 * Names are sanitized: any character outside `[A-Za-z0-9_-]` is replaced
 * with `-`, consecutive replacements collapse, leading/trailing dashes are
 * trimmed. If the sanitized result is empty, the entire `--<name>` segment
 * is omitted. This neutralizes path-traversal attempts via names like
 * `../../etc/passwd`.
 */
object BackupNaming {
    private const val PREFIX = "world-"

    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(
        ZoneOffset.UTC
    )

    private val SAFE_NAME_REGEX = Regex("[^A-Za-z0-9_-]+")

    fun fileName(timestamp: Instant, name: String?, format: ArchiveFormat): String {
        val ts = TIMESTAMP_FORMAT.format(timestamp)
        val cleanName = name?.let { sanitizeName(it) }?.takeIf { it.isNotEmpty() }
        val nameSuffix = cleanName?.let { "--$it" } ?: ""
        val ext = when (format) {
            ArchiveFormat.TAR_ZST -> "tar.zst"
            ArchiveFormat.ZIP -> "zip"
        }

        return "$PREFIX$ts$nameSuffix.$ext"
    }

    fun sanitizeName(name: String): String =
        name.replace(SAFE_NAME_REGEX, "-").trim('-')
}
