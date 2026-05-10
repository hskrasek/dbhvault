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
 * loops with a `delay(intervalHours)` between iterations and invokes [runBackup]
 * each tick (subject to skip predicates).
 *
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/schedule/BackupSchedulerTest.kt`
 *
 * Behaviors specified by the test suite:
 *
 *  - On [start], launch a coroutine that loops `delay(intervalHours.hours) → tick`.
 *    The first tick fires AFTER the first delay (no immediate fire on start).
 *  - Each tick:
 *      1. If `!scheduleConfig.enabled`, skip (don't call [runBackup]).
 *      2. If [shouldSkipIdle]() returns `true`, skip.
 *      3. Otherwise, invoke [runBackup]([BackupRequest.Scheduled]).
 *  - [updateConfig] swaps the config atomically; subsequent ticks use the new
 *    interval. The currently-pending `delay` is NOT shortened — the change
 *    takes effect on the iteration after the current one completes.
 *  - [stop] cancels the running job. No further ticks fire.
 *  - Calling [start] when already running cancels the previous job and replaces
 *    it (no double-firing).
 *  - Exceptions thrown by [runBackup] are caught (logged) and do NOT kill the
 *    scheduler — the next tick must still fire.
 *
 * Suggested approach: `AtomicReference<Job?>` holds the current job;
 * `@Volatile` on a config field for safe cross-thread reads.
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
