package dev.skrasek.dbhvault.backup

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Outcome of a single backup attempt. */
sealed class BackupResult {
    /**
     * The backup completed successfully.
     *
     * @property file resulting archive path under the configured backup directory
     * @property sizeBytes size of [file] in bytes
     * @property timestamp when the backup was taken (from the orchestrator's clock)
     * @property duration wall-clock time elapsed inside the orchestrator
     * @property pinned true if this was a named manual backup (immune to retention)
     */
    data class Success(
        val file: Path,
        val sizeBytes: Long,
        val timestamp: Instant,
        val duration: Duration,
        val pinned: Boolean,
    ) : BackupResult()

    /** The backup did not run; see [reason] for why. */
    data class Skipped(val reason: SkipReason) : BackupResult()

    /** The backup attempted to run but failed. The partial archive (if any) has been cleaned up. */
    data class Failed(val cause: Throwable) : BackupResult()

    enum class SkipReason {
        /** Another backup was already in progress. */
        ALREADY_RUNNING,

        /** The world has been idle and no changes have occurred since the last backup. */
        WORLD_IDLE_AND_CLEAN,

        /** The schedule is paused via config. */
        SCHEDULE_DISABLED,
    }
}
