package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.config.IdleSkipConfig
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the most recent moment a player was known to be on the server.
 * Thread-safe via [AtomicReference] (Fabric callbacks for player
 * join/disconnect can fire from the network thread, while
 * [shouldSkipScheduled] is read from the scheduler coroutine).
 *
 * [recordActivity] is called on BOTH join and disconnect — either event
 * proves a player was online at that instant. (Join-only tracking was the
 * old behavior; it froze activity at session *start*, so a mid-session
 * backup "satisfied" the dirty check below and the tail of a long session
 * could be skipped forever.)
 *
 * This tracker is edge-triggered: a player who stays online for days
 * produces no events, so callers must ALSO check the live player list —
 * players currently online means not idle, regardless of what this says.
 *
 * [shouldSkipScheduled] returns true only when ALL of:
 *   1. `config.enabled` is true
 *   2. `now - lastPlayerActivity >= config.afterIdleHours` (inclusive boundary)
 *   3. `lastBackup != null && lastBackup.isAfter(lastPlayerActivity)`
 *
 * Condition 3 is the world-dirty rule: if a player was online since the
 * last backup, we MUST take a fresh backup to capture that activity.
 * Skipping forever after a player leaves would lose their last session.
 */
class IdleTracker(initialActivity: Instant) {
    private val lastActivity = AtomicReference(initialActivity)

    val lastPlayerActivity: Instant get() = lastActivity.get()

    /** Record that a player was present at [now]. */
    fun recordActivity(now: Instant) {
        lastActivity.set(now)
    }

    fun shouldSkipScheduled(
        config: IdleSkipConfig,
        lastBackup: Instant?,
        now: Instant,
    ): Boolean {
        if (!config.enabled) return false

        val activity = lastActivity.get()
        val idleFor = Duration.between(activity, now)

        if (idleFor < Duration.ofHours(config.afterIdleHours.toLong())) return false

        return lastBackup != null && lastBackup.isAfter(activity)
    }
}
