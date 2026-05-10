package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.backup.BackupRequest
import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.IdleSkipConfig
import dev.skrasek.dbhvault.config.ScheduleConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSchedulerTest {

    private fun enabledHourlyConfig(intervalHours: Int = 1) = ScheduleConfig(
        enabled = true,
        intervalHours = intervalHours,
        idleSkip = IdleSkipConfig(enabled = false),
    )

    // ---- Firing ----

    @Test
    fun `fires Scheduled request at the first interval boundary`() = runTest {
        val captured = mutableListOf<BackupRequest>()
        val scheduler = BackupScheduler(
            scheduleConfig = enabledHourlyConfig(intervalHours = 1),
            shouldSkipIdle = { false },
            runBackup = { req ->
                captured.add(req)
                BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)
            },
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(1).toMillis())
        runCurrent()
        scheduler.stop()

        assertEquals(listOf<BackupRequest>(BackupRequest.Scheduled), captured)
    }

    @Test
    fun `fires multiple backups across multiple intervals`() = runTest {
        val count = AtomicInteger()
        val scheduler = BackupScheduler(
            scheduleConfig = enabledHourlyConfig(intervalHours = 1),
            shouldSkipIdle = { false },
            runBackup = {
                count.incrementAndGet()
                BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)
            },
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(3).toMillis())
        runCurrent()
        scheduler.stop()

        assertEquals(3, count.get(), "expected one backup per hourly interval over 3 hours")
    }

    // ---- Skip predicates ----

    @Test
    fun `does not fire when schedule is disabled`() = runTest {
        val count = AtomicInteger()
        val scheduler = BackupScheduler(
            scheduleConfig = ScheduleConfig(
                enabled = false,
                intervalHours = 1,
                idleSkip = IdleSkipConfig(enabled = false),
            ),
            shouldSkipIdle = { false },
            runBackup = {
                count.incrementAndGet()
                BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)
            },
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(3).toMillis())
        runCurrent()
        scheduler.stop()

        assertEquals(0, count.get(), "disabled schedule must not invoke runBackup")
    }

    @Test
    fun `does not fire when shouldSkipIdle returns true`() = runTest {
        val count = AtomicInteger()
        val scheduler = BackupScheduler(
            scheduleConfig = enabledHourlyConfig(intervalHours = 1),
            shouldSkipIdle = { true },
            runBackup = {
                count.incrementAndGet()
                BackupResult.Skipped(BackupResult.SkipReason.WORLD_IDLE_AND_CLEAN)
            },
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(3).toMillis())
        runCurrent()
        scheduler.stop()

        assertEquals(0, count.get(), "shouldSkipIdle=true must short-circuit runBackup")
    }

    // ---- Config update ----

    @Test
    fun `updateConfig takes effect after the currently-pending delay completes`() = runTest {
        // The currently-running iteration's delay was already scheduled with the
        // old interval — it cannot be shortened. The new interval kicks in on the
        // iteration AFTER the currently-pending one completes.
        val count = AtomicInteger()
        val scheduler = BackupScheduler(
            scheduleConfig = enabledHourlyConfig(intervalHours = 6),
            shouldSkipIdle = { false },
            runBackup = {
                count.incrementAndGet()
                BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)
            },
        )

        scheduler.start(this)

        // First tick at 6h. After this tick, iteration 2 starts delaying for 6h
        // (using the original config that was current when iteration 2 read it).
        advanceTimeBy(Duration.ofHours(6).toMillis())
        runCurrent()
        assertEquals(1, count.get(), "first tick at 6h")

        // Operator changes interval to 1h. Iteration 2 is mid-delay with the old 6h.
        scheduler.updateConfig(enabledHourlyConfig(intervalHours = 1))

        // Iteration 2 still fires at the original +6h boundary (=12h total).
        advanceTimeBy(Duration.ofHours(6).toMillis())
        runCurrent()
        assertEquals(2, count.get(), "iteration 2 honors the already-scheduled 6h delay")

        // Iteration 3 reads the NEW config and delays 1h.
        advanceTimeBy(Duration.ofHours(1).toMillis())
        runCurrent()
        assertEquals(3, count.get(), "iteration 3 picks up the new 1h interval")

        scheduler.stop()
    }

    // ---- Lifecycle ----

    @Test
    fun `stop prevents further ticks`() = runTest {
        val count = AtomicInteger()
        val scheduler = BackupScheduler(
            scheduleConfig = enabledHourlyConfig(intervalHours = 1),
            shouldSkipIdle = { false },
            runBackup = {
                count.incrementAndGet()
                BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)
            },
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(2).toMillis())
        runCurrent()
        val countAtStop = count.get()
        scheduler.stop()
        advanceTimeBy(Duration.ofHours(10).toMillis())
        runCurrent()

        assertEquals(countAtStop, count.get(), "no ticks should fire after stop()")
    }

    @Test
    fun `start while running cancels the previous job to avoid double-firing`() = runTest {
        val count = AtomicInteger()
        val scheduler = BackupScheduler(
            scheduleConfig = enabledHourlyConfig(intervalHours = 1),
            shouldSkipIdle = { false },
            runBackup = {
                count.incrementAndGet()
                BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)
            },
        )

        scheduler.start(this)
        scheduler.start(this) // second start; first must be cancelled
        advanceTimeBy(Duration.ofHours(3).toMillis())
        runCurrent()
        scheduler.stop()

        assertEquals(
            3,
            count.get(),
            "expected 3 backups (one per hour), not 6 — second start should have replaced the first",
        )
    }

    // ---- Resilience ----

    @Test
    fun `exception in runBackup does not kill the scheduler`() = runTest {
        val count = AtomicInteger()
        val scheduler = BackupScheduler(
            scheduleConfig = enabledHourlyConfig(intervalHours = 1),
            shouldSkipIdle = { false },
            runBackup = {
                val n = count.incrementAndGet()
                if (n == 1) throw RuntimeException("simulated tick-1 failure")
                BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)
            },
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(3).toMillis())
        runCurrent()
        scheduler.stop()

        assertTrue(
            count.get() >= 3,
            "scheduler must keep ticking after a failed runBackup; got count=${count.get()}",
        )
    }
}
