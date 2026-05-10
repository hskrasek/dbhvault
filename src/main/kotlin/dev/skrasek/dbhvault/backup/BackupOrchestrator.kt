package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.backup.archive.BackupArchiver
import dev.skrasek.dbhvault.backup.storage.BackupEntry
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.backup.storage.RetentionPolicy
import dev.skrasek.dbhvault.config.Config
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates a single backup attempt: lock → freeze → archive → thaw → retention → prune.
 *
 * The lock (an [AtomicBoolean]) ensures concurrent invocations short-circuit with
 * [BackupResult.Skipped]([BackupResult.SkipReason.ALREADY_RUNNING]).
 *
 * [freeze] is invoked under a try/finally so the world's autosave is always
 * re-enabled — without this, a thrown archiver could leave `noSave=true` on
 * every loaded level, silently disabling vanilla persistence until restart.
 *
 * [freeze] and [prune] are constructor-injected so tests can substitute
 * lightweight stand-ins without a real `MinecraftServer` or filesystem deletion.
 *
 * `pinned` in [BackupResult.Success] reflects the *request's* intent
 * ([BackupRequest.Manual] with non-null name), independent of whether the
 * sanitized filename ends up retaining a `--<name>` suffix.
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
    private val logger = LoggerFactory.getLogger(BackupOrchestrator::class.java)
    private val running = AtomicBoolean(false)

    fun runIfFree(request: BackupRequest): BackupResult {
        if (!running.compareAndSet(false, true)) {
            return BackupResult.Skipped(BackupResult.SkipReason.ALREADY_RUNNING)
        }

        return try {
            execute(request)
        } finally {
            running.set(false)
        }
    }

    private fun execute(request: BackupRequest): BackupResult {
        val started = clock.instant()
        val name = (request as? BackupRequest.Manual)?.name
        val pinned = name != null
        val fileName = BackupNaming.fileName(started, name, config.compression.format)
        val destFile = backupDir.resolve(fileName)

        backupDir.toFile().mkdirs()

        return try {
            val token = freeze()
            try {
                val sizeBytes = archiver.archive(worldDir, destFile, config.compression.level)
                val finished = clock.instant()
                val all = registry.list()
                val decision = retention.classify(all, finished)

                prune(decision.prune)

                BackupResult.Success(
                    file = destFile,
                    sizeBytes = sizeBytes,
                    timestamp = started,
                    duration = Duration.between(started, finished),
                    pinned = pinned,
                )
            } finally {
                runCatching { token.close() }.onFailure { logger.error("thaw failed", it) }
            }
        } catch (t: Throwable) {
            logger.error("Backup failed", t)
            runCatching { destFile.toFile().delete() }
            BackupResult.Failed(t)
        }
    }
}
