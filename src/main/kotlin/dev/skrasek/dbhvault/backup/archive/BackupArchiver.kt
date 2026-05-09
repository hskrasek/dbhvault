package dev.skrasek.dbhvault.backup.archive

import java.nio.file.Path

/**
 * Streams the contents of a directory into a single archive file.
 *
 * Implementations are responsible for:
 *  - Walking [sourceDir] recursively and including every regular file.
 *  - Encoding entry paths with forward slashes regardless of platform
 *    (so archives produced on Windows extract correctly on Linux/macOS).
 *  - Honoring [level] as the compression level appropriate to the format
 *    (e.g., 0 = no compression, 9 = max compression for Deflate).
 *  - Returning the size in bytes of the resulting archive at [destFile].
 *
 * Implementations MUST NOT attempt to coordinate world-save state — that's
 * the orchestrator's job. By the time this is called, the world is expected
 * to be flushed and frozen, and the source tree must not be written
 * concurrently. Behavior under concurrent modification is undefined.
 */
interface BackupArchiver {
    fun archive(sourceDir: Path, destFile: Path, level: Int): Long
}
