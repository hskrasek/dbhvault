package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.config.IdleSkipConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class IdleTrackerTest {

    private val now = Instant.parse("2026-05-09T12:00:00Z")
    private val cfg = IdleSkipConfig(enabled = true, afterIdleHours = 24)

    // ---- recordActivity behavior ----

    @Test
    fun `constructor seeds lastPlayerActivity with the initial value`() {
        val initial = Instant.parse("2026-01-01T00:00:00Z")
        val tracker = IdleTracker(initialActivity = initial)
        assertEquals(initial, tracker.lastPlayerActivity)
    }

    @Test
    fun `recordActivity advances lastPlayerActivity to now`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(7)))
        tracker.recordActivity(now)
        assertEquals(now, tracker.lastPlayerActivity)
    }

    @Test
    fun `disconnect updates activity to the disconnect moment not the join moment`() {
        // A player joins, plays for 10 hours, and leaves. Activity must reflect
        // the END of the session — otherwise a mid-session backup "satisfies"
        // the dirty check and the session tail is never captured.
        val joinAt = now.minus(Duration.ofHours(10))
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(30)))

        tracker.recordActivity(joinAt) // join
        tracker.recordActivity(now) // disconnect
        assertEquals(now, tracker.lastPlayerActivity)
    }

    // ---- shouldSkipScheduled: disabled ----

    @Test
    fun `disabled config never skips`() {
        val disabled = IdleSkipConfig(enabled = false, afterIdleHours = 1)
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(30)))

        assertFalse(
            tracker.shouldSkipScheduled(disabled, lastBackup = now.minus(Duration.ofDays(1)), now = now),
            "disabled config must never trigger skip even if all other conditions hold",
        )
    }

    // ---- shouldSkipScheduled: not idle long enough ----

    @Test
    fun `recent activity means not skipped`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(7)))
        tracker.recordActivity(now)

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = now.minus(Duration.ofDays(7)), now = now),
            "with activity at now, idle duration is zero",
        )
    }

    @Test
    fun `idle for less than threshold is not skipped`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofHours(5)))

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = now.minus(Duration.ofHours(2)), now = now),
            "idle duration 5h < threshold 24h",
        )
    }

    // ---- shouldSkipScheduled: world-dirty rules ----

    @Test
    fun `idle past threshold without prior backup is not skipped`() {
        // World became idle 2 days ago, but no backup has ever been taken since
        // — we MUST take one to capture the post-activity state.
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(2)))

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = null, now = now),
            "no prior backup means we must capture the last-activity state",
        )
    }

    @Test
    fun `idle past threshold with backup taken AFTER last activity is skipped`() {
        // Activity ended 2 days ago; backup was taken 1 day ago (after activity).
        // Nothing new to capture — skip.
        val activityEnded = now.minus(Duration.ofDays(2))
        val tracker = IdleTracker(initialActivity = activityEnded)
        val lastBackup = now.minus(Duration.ofDays(1))

        assertTrue(
            tracker.shouldSkipScheduled(cfg, lastBackup = lastBackup, now = now),
            "world is idle and last backup post-dates last activity → skip",
        )
    }

    @Test
    fun `idle past threshold with backup taken BEFORE last activity is not skipped`() {
        // Last backup was a week ago, but a player was online 2 days ago and
        // then left. World is dirty — must take a fresh backup.
        val backupTakenAt = now.minus(Duration.ofDays(7))
        val activityEnded = now.minus(Duration.ofDays(2))
        val tracker = IdleTracker(initialActivity = activityEnded)

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = backupTakenAt, now = now),
            "backup pre-dates last activity → world is dirty → must capture",
        )
    }

    @Test
    fun `backup mid-session does not satisfy the dirty check once the session end is recorded`() {
        // Regression for the session-tail data-loss bug: join at T0, backup at
        // T0+6h, player leaves at T0+10h. The disconnect must move activity to
        // T0+10h so the mid-session backup no longer post-dates activity.
        val joinAt = now.minus(Duration.ofHours(40))
        val midSessionBackup = now.minus(Duration.ofHours(34))
        val leaveAt = now.minus(Duration.ofHours(30))

        val tracker = IdleTracker(initialActivity = joinAt)
        tracker.recordActivity(leaveAt) // disconnect edge

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = midSessionBackup, now = now),
            "mid-session backup pre-dates the recorded session end → world dirty → must capture",
        )
    }

    @Test
    fun `backup taken at exactly the same instant as last activity is not skipped`() {
        // Edge: lastBackup == lastPlayerActivity. The strict "isAfter" rule says
        // backup must be STRICTLY after activity to count as having captured it.
        val sameInstant = now.minus(Duration.ofDays(2))
        val tracker = IdleTracker(initialActivity = sameInstant)

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = sameInstant, now = now),
            "lastBackup == lastPlayerActivity does NOT count as captured (strict isAfter)",
        )
    }

    // ---- Boundary ----

    @Test
    fun `idle exactly at the afterIdleHours threshold counts as past threshold`() {
        // Inclusive boundary: 24h idle with afterIdleHours=24 should be eligible
        // for skip (assuming the lastBackup condition is met).
        val activityEnded = now.minus(Duration.ofHours(24))
        val tracker = IdleTracker(initialActivity = activityEnded)
        val lastBackup = now.minus(Duration.ofHours(12))

        assertTrue(
            tracker.shouldSkipScheduled(cfg, lastBackup = lastBackup, now = now),
            "idle 24h with threshold 24h must count (inclusive)",
        )
    }
}
