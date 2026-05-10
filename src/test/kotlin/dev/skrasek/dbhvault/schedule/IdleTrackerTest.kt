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

    // ---- playerCountChanged behavior ----

    @Test
    fun `constructor seeds lastPlayerActivity with the initial value`() {
        val initial = Instant.parse("2026-01-01T00:00:00Z")
        val tracker = IdleTracker(initialActivity = initial)
        assertEquals(initial, tracker.lastPlayerActivity)
    }

    @Test
    fun `playerCountChanged with non-zero count advances lastPlayerActivity to now`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(7)))
        tracker.playerCountChanged(playerCount = 3, now = now)
        assertEquals(now, tracker.lastPlayerActivity)
    }

    @Test
    fun `playerCountChanged with zero count freezes lastPlayerActivity`() {
        val frozenAt = now.minus(Duration.ofMinutes(5))
        val tracker = IdleTracker(initialActivity = frozenAt)
        tracker.playerCountChanged(playerCount = 0, now = now)
        assertEquals(
            frozenAt,
            tracker.lastPlayerActivity,
            "zero players must not advance the activity timestamp",
        )
    }

    @Test
    fun `transitions online to offline to online track correctly`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(30)))

        tracker.playerCountChanged(1, now.minus(Duration.ofMinutes(10)))
        assertEquals(now.minus(Duration.ofMinutes(10)), tracker.lastPlayerActivity)

        tracker.playerCountChanged(0, now.minus(Duration.ofMinutes(5)))
        // Frozen at the previous online moment, NOT the disconnect moment.
        assertEquals(now.minus(Duration.ofMinutes(10)), tracker.lastPlayerActivity)

        tracker.playerCountChanged(2, now.minus(Duration.ofMinutes(1)))
        assertEquals(now.minus(Duration.ofMinutes(1)), tracker.lastPlayerActivity)
    }

    // ---- shouldSkipScheduled: disabled ----

    @Test
    fun `disabled config never skips`() {
        val disabled = IdleSkipConfig(enabled = false, afterIdleHours = 1)
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(30)))
        tracker.playerCountChanged(0, now.minus(Duration.ofDays(30)))

        assertFalse(
            tracker.shouldSkipScheduled(disabled, lastBackup = now.minus(Duration.ofDays(1)), now = now),
            "disabled config must never trigger skip even if all other conditions hold",
        )
    }

    // ---- shouldSkipScheduled: not idle long enough ----

    @Test
    fun `online player means not skipped`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(7)))
        tracker.playerCountChanged(1, now)

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = now.minus(Duration.ofDays(7)), now = now),
            "with a player online, lastPlayerActivity == now, so idle duration is zero",
        )
    }

    @Test
    fun `idle for less than threshold is not skipped`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofHours(5)))
        tracker.playerCountChanged(0, now.minus(Duration.ofHours(5)))

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
        tracker.playerCountChanged(0, now.minus(Duration.ofDays(2)))

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
        tracker.playerCountChanged(0, activityEnded)
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
        tracker.playerCountChanged(0, activityEnded)

        assertFalse(
            tracker.shouldSkipScheduled(cfg, lastBackup = backupTakenAt, now = now),
            "backup pre-dates last activity → world is dirty → must capture",
        )
    }

    @Test
    fun `backup taken at exactly the same instant as last activity is not skipped`() {
        // Edge: lastBackup == lastPlayerActivity. The strict "isAfter" rule says
        // backup must be STRICTLY after activity to count as having captured it.
        val sameInstant = now.minus(Duration.ofDays(2))
        val tracker = IdleTracker(initialActivity = sameInstant)
        tracker.playerCountChanged(0, sameInstant)

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
        tracker.playerCountChanged(0, activityEnded)
        val lastBackup = now.minus(Duration.ofHours(12))

        assertTrue(
            tracker.shouldSkipScheduled(cfg, lastBackup = lastBackup, now = now),
            "idle 24h with threshold 24h must count (inclusive)",
        )
    }
}
