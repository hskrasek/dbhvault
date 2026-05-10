package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.backup.BackupRequest
import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.ScheduleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Coroutine-based interval scheduler. On [start], launches a coroutine that
 * loops `delay(intervalHours.hours) → tick`. The first tick fires after the
 * first delay (no immediate fire on start).
 *
 * On each tick, skips when `!scheduleConfig.enabled` or when [shouldSkipIdle]()
 * returns true; otherwise invokes [runBackup]([BackupRequest.Scheduled]).
 * Exceptions from [runBackup] are caught and logged — the scheduler keeps ticking.
 *
 * [updateConfig] swaps the config atomically, but the currently-pending `delay`
 * is NOT shortened — the new interval takes effect on the iteration after the
 * current one completes.
 *
 * [start] when already running cancels the previous job (no double-firing).
 */
class BackupScheduler(
    @Volatile private var scheduleConfig: ScheduleConfig,
    private val shouldSkipIdle: () -> Boolean,
    private val runBackup: suspend (BackupRequest) -> BackupResult,
) {
    private val logger = LoggerFactory.getLogger(BackupScheduler::class.java)

    private val jobRef = AtomicReference<Job?>(null)

    fun start(scope: CoroutineScope): Unit {
        val newJob = scope.launch {
            while (isActive) {
                val cfg = scheduleConfig
                val intervalMs = Duration.ofHours(cfg.intervalHours.toLong()).toMillis()
                delay(intervalMs)

                if (!cfg.enabled) {
                    logger.debug("Schedule disabled; tick ignored")
                    continue
                }

                if (shouldSkipIdle()) {
                    logger.info("Skipping scheduled backup: world idle and clean since last backup")
                    continue
                }

                try {
                    runBackup(BackupRequest.Scheduled)
                } catch (t: Throwable) {
                    logger.error("Scheduled backup runner threw", t)
                }
            }
        }

        val previous = jobRef.getAndSet(newJob)
        previous?.cancel()
    }

    fun updateConfig(cfg: ScheduleConfig): Unit {
        scheduleConfig = cfg
    }

    fun stop(): Unit {
        jobRef.getAndSet(null)?.cancel()
    }
}
