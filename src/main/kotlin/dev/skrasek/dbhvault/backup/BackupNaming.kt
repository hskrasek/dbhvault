package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import java.time.Instant

/**
 * Generates archive filenames from a timestamp, optional name, and format.
 *
 * Stub awaiting implementation. The companion test in
 * `src/test/kotlin/dev/skrasek/dbhvault/backup/BackupNamingTest.kt`
 * specifies the contract. A few highlights from the tests:
 *
 *  - Format: `world-<UTC-timestamp>(--<sanitized-name>)?.<ext>`
 *  - Timestamp pattern: `yyyy-MM-dd'T'HH-mm-ss'Z'` (note dashes for time
 *    separators; colons aren't filesystem-safe on Windows).
 *  - Sub-second precision is dropped — only whole seconds appear in the name.
 *  - Names are filesystem-sanitized: any character outside `[A-Za-z0-9_-]` is
 *    replaced with `-`; consecutive replacements collapse to a single dash;
 *    leading and trailing dashes are trimmed.
 *  - If the sanitized result is empty (input was null, empty, whitespace, or
 *    only special chars), the entire `--<name>` segment is omitted.
 *  - Path-separator characters (`/`, `\`) are NOT special-cased — they fall
 *    out naturally because they're outside the safe-character set, but the
 *    test calls this out explicitly because it's a security property.
 *
 * Extensions: TAR_ZST -> "tar.zst", ZIP -> "zip".
 */
object BackupNaming {
    fun fileName(timestamp: Instant, name: String?, format: ArchiveFormat): String =
        TODO("Implement: emit world-<timestamp>(--<sanitized-name>)?.<ext>")
}
