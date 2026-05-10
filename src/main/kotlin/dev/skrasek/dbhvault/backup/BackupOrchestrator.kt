package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.backup.archive.BackupArchiver
import dev.skrasek.dbhvault.backup.storage.BackupEntry
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.backup.storage.RetentionPolicy
import dev.skrasek.dbhvault.config.Config
import java.nio.file.Path
import java.time.Clock

/**
 * Coordinates a single backup attempt: lock → freeze → archive → thaw → retention → prune.
 *
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/backup/BackupOrchestratorTest.kt`
 *
 * Pipeline (in order):
 *  1. **Acquire lock.** Use an `AtomicBoolean.compareAndSet(false, true)`.
 *     If it's already true, return [BackupResult.Skipped] with [BackupResult.SkipReason.ALREADY_RUNNING].
 *  2. **Compute filename.** Use [BackupNaming.fileName] with the orchestrator's [clock]
 *     and the request's name (null for [BackupRequest.Scheduled], the supplied name for
 *     [BackupRequest.Manual]).
 *  3. **Ensure backup directory exists.** `Files.createDirectories(backupDir)`.
 *  4. **Freeze the world.** Call [freeze]() to flush + suspend autosave; the returned
 *     [AutoCloseable] thaws when closed. MUST be closed in a `finally` so a thrown
 *     archiver still re-enables autosave — the test "thaw called even when archive throws"
 *     enforces this.
 *  5. **Archive.** Call [archiver].archive(worldDir, destFile, config.compression.level).
 *  6. **Retention.** registry.list() → retention.classify(...) → prune(decision.prune).
 *  7. **Return Success.** with file, size, timestamp, duration, pinned flag.
 *  8. **On any throwable inside step 5–7:** delete the partial dest file if present,
 *     return [BackupResult.Failed] with the cause.
 *  9. **Always:** release the lock (via `try { ... } finally { running.set(false) }`).
 *
 * `pinned` in [BackupResult.Success] reflects whether the request was [BackupRequest.Manual]
 * with a non-null name (i.e., the request asked for pinning, regardless of whether the
 * sanitized filename ends up retaining a `--<name>` suffix).
 *
 * The [freeze] and [prune] callbacks are constructor-injected so tests can inject test
 * doubles without needing a real `MinecraftServer` or filesystem deletion.
 */
class BackupOrchestrator(
    private val config: Config,
    private val worldDir: Path,
    private val backupDir: Path,
    private val archiver: BackupArchiver,
    private val registry: BackupRegistry,
    private val retention: RetentionPolicy,
    private val clock: Clock,
    private val freeze: () -> AutoCloseable,
    private val prune: (List<BackupEntry>) -> Unit,
) {
    fun runIfFree(request: BackupRequest): BackupResult =
        TODO("Implement: lock + freeze → archive → thaw → registry → retention → prune (see KDoc)")
}
