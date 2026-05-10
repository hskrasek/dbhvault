package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.config.IdleSkipConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the most recent moment a player was on the server. Thread-safe via
 * [AtomicReference] (Fabric callbacks for player join/disconnect can fire
 * from the network thread, while [shouldSkipScheduled] is read from the
 * scheduler coroutine).
 *
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/schedule/IdleTrackerTest.kt`
 *
 * Behaviors specified by the test suite:
 *
 *  - Constructor seeds `lastPlayerActivity` with [initialActivity].
 *  - [playerCountChanged] with `playerCount > 0` advances `lastPlayerActivity`
 *    to `now`. With `playerCount == 0`, it freezes (no update) so the field
 *    holds the moment the last player left.
 *  - [shouldSkipScheduled] returns `true` only when ALL of:
 *      1. `config.enabled` is `true`
 *      2. `now - lastPlayerActivity >= config.afterIdleHours.hours`
 *      3. `lastBackup != null && lastBackup.isAfter(lastPlayerActivity)`
 *    Otherwise returns `false`.
 *  - The "boundary inclusive" semantics: `now - lastPlayerActivity == afterIdleHours`
 *    counts as "past threshold" and may trigger a skip if condition 3 holds.
 *  - The world-dirty rule: if a player was online since the last backup
 *    (lastBackup precedes lastPlayerActivity), we MUST take a fresh backup —
 *    skipping would lose the last activity. Only after the post-activity
 *    backup has been captured do subsequent ticks skip.
 */
class IdleTracker(initialActivity: Instant) {
    private val lastActivity = AtomicReference(initialActivity)

    val lastPlayerActivity: Instant get() = lastActivity.get()

    fun playerCountChanged(playerCount: Int, now: Instant): Unit =
        TODO("Implement: when playerCount > 0, set lastActivity = now; otherwise freeze (no update)")

    fun shouldSkipScheduled(
        config: IdleSkipConfig,
        lastBackup: Instant?,
        now: Instant,
    ): Boolean = TODO("Implement: skip when enabled AND idle past threshold AND lastBackup > activity")
}
