package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.backup.BackupMetadata
import dev.skrasek.dbhvault.config.ArchiveFormat
import dev.skrasek.dbhvault.config.RetentionConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class HybridRetentionTest {

    private val now = Instant.parse("2026-05-09T00:00:00Z")

    // ---- Happy paths ----

    @Test
    fun `empty input produces empty decision`() {
        val policy = HybridRetention(RetentionConfig(keepLast = 5, keepWithinDays = 30))
        val result = policy.classify(emptyList(), now)
        assertEquals(0, result.keep.size)
        assertEquals(0, result.prune.size)
    }

    @Test
    fun `fewer entries than keepLast and all within age window keeps everything`() {
        val policy = HybridRetention(RetentionConfig(keepLast = 10, keepWithinDays = 30))
        val entries = (0 until 3).map { entry(now.minus(Duration.ofDays(it.toLong()))) }

        val result = policy.classify(entries, now)
        assertEquals(3, result.keep.size)
        assertEquals(0, result.prune.size)
    }

    // ---- Hybrid logic ----

    @Test
    fun `keepLast wins when no entries fall in age window`() {
        // 4 backups all older than 5 days; keepLast=3 → keep 3 newest.
        val policy = HybridRetention(RetentionConfig(keepLast = 3, keepWithinDays = 5))
        val entries = (10 until 14).map { entry(now.minus(Duration.ofDays(it.toLong()))) }

        val result = policy.classify(entries, now)
        assertEquals(3, result.keep.size)
        assertEquals(1, result.prune.size)

        // The pruned one should be the oldest (13 days old).
        assertEquals(now.minus(Duration.ofDays(13)), result.prune.first().metadata.timestamp)
    }

    @Test
    fun `keepWithinDays wins when many entries are within age window`() {
        // 10 entries spread over 9 days; keepLast=3, keepWithinDays=10 → age wins
        val policy = HybridRetention(RetentionConfig(keepLast = 3, keepWithinDays = 10))
        val entries = (0 until 10).map { entry(now.minus(Duration.ofDays(it.toLong()))) }

        val result = policy.classify(entries, now)
        assertEquals(10, result.keep.size)
        assertEquals(0, result.prune.size)
    }

    @Test
    fun `hybrid keeps the larger of count and age pools`() {
        // 10 daily backups going back 9 days; keepLast=2, keepWithinDays=5
        // age-based: 6 entries (days 0..5)
        // count-based: 2 entries (days 0..1)
        // hybrid: take age (6 > 2)
        val policy = HybridRetention(RetentionConfig(keepLast = 2, keepWithinDays = 5))
        val entries = (0 until 10).map { entry(now.minus(Duration.ofDays(it.toLong()))) }

        val result = policy.classify(entries, now)
        assertEquals(6, result.keep.size, "expected 6 kept (age window wins); got ${result.keep.size}")
        assertEquals(4, result.prune.size)
    }

    // ---- Pinned backups ----

    @Test
    fun `pinned backup older than age window is preserved`() {
        val policy = HybridRetention(RetentionConfig(keepLast = 2, keepWithinDays = 5))
        val entries = listOf(
            entry(now.minus(Duration.ofDays(100)), name = "release-v1"),  // ancient pinned
            entry(now),
            entry(now.minus(Duration.ofDays(1))),
        )

        val result = policy.classify(entries, now)
        assertTrue(
            result.keep.any { it.metadata.name == "release-v1" },
            "ancient pinned entry should be kept",
        )
        assertTrue(
            result.prune.none { it.metadata.isPinned },
            "no pinned entry should ever be pruned",
        )
    }

    @Test
    fun `pinned backup beyond keepLast count is preserved`() {
        // keepLast=2 with 5 entries — but 1 is pinned. Pinned never counts.
        val policy = HybridRetention(RetentionConfig(keepLast = 2, keepWithinDays = 0))
        val entries = listOf(
            entry(now.minus(Duration.ofDays(0))),
            entry(now.minus(Duration.ofDays(1))),
            entry(now.minus(Duration.ofDays(2))),
            entry(now.minus(Duration.ofDays(3))),
            entry(now.minus(Duration.ofDays(50)), name = "preserved"),
        )

        val result = policy.classify(entries, now)
        // 2 newest scheduled (days 0, 1) + 1 pinned = 3 kept; 2 pruned (days 2, 3)
        assertEquals(3, result.keep.size)
        assertEquals(2, result.prune.size)
        assertTrue(result.keep.any { it.metadata.name == "preserved" })
    }

    @Test
    fun `pinned backups do not consume keepLast quota`() {
        // keepLast=3 with 4 unpinned + 1 pinned. Quota should apply to unpinned only,
        // so we keep 3 unpinned + 1 pinned = 4 total, prune 1 unpinned.
        val policy = HybridRetention(RetentionConfig(keepLast = 3, keepWithinDays = 0))
        val entries = listOf(
            entry(now.minus(Duration.ofDays(0))),
            entry(now.minus(Duration.ofDays(1))),
            entry(now.minus(Duration.ofDays(2))),
            entry(now.minus(Duration.ofDays(3))),
            entry(now.minus(Duration.ofDays(4)), name = "anchor"),
        )

        val result = policy.classify(entries, now)
        assertEquals(4, result.keep.size)
        assertEquals(1, result.prune.size)
        assertEquals(now.minus(Duration.ofDays(3)), result.prune.first().metadata.timestamp)
    }

    @Test
    fun `all pinned backups are kept regardless of size`() {
        val policy = HybridRetention(RetentionConfig(keepLast = 1, keepWithinDays = 1))
        val entries = (0 until 20).map {
            entry(now.minus(Duration.ofDays((it * 30).toLong())), name = "pinned-$it")
        }

        val result = policy.classify(entries, now)
        assertEquals(20, result.keep.size)
        assertEquals(0, result.prune.size)
    }

    // ---- Edge cases ----

    @Test
    fun `unsorted input is classified correctly`() {
        // Input deliberately shuffled — impl must sort internally.
        val policy = HybridRetention(RetentionConfig(keepLast = 2, keepWithinDays = 0))
        val entries = listOf(
            entry(now.minus(Duration.ofDays(5))),
            entry(now),
            entry(now.minus(Duration.ofDays(2))),
            entry(now.minus(Duration.ofDays(10))),
            entry(now.minus(Duration.ofDays(1))),
        )

        val result = policy.classify(entries, now)
        assertEquals(2, result.keep.size)
        // The two kept should be the newest two regardless of input order.
        val keptTimestamps = result.keep.map { it.metadata.timestamp }.toSet()
        assertEquals(setOf(now, now.minus(Duration.ofDays(1))), keptTimestamps)
    }

    @Test
    fun `entry exactly at keepWithinDays cutoff is kept inclusive boundary`() {
        // Off-by-one trap: an entry timestamped exactly `now - keepWithinDays`
        // is on the boundary. Specify inclusive — operator says "keep 5 days"
        // and intuitively expects the 5-day-old backup to be kept.
        val policy = HybridRetention(RetentionConfig(keepLast = 1, keepWithinDays = 5))
        val cutoff = now.minus(Duration.ofDays(5))
        val entries = listOf(
            entry(cutoff),  // exactly at boundary
            entry(now),     // newest
        )

        val result = policy.classify(entries, now)
        assertEquals(2, result.keep.size, "boundary entry must be kept (inclusive cutoff)")
        assertEquals(0, result.prune.size)
    }

    @Test
    fun `keep plus prune equals input`() {
        // Sanity: every entry must be classified, never lost.
        val policy = HybridRetention(RetentionConfig(keepLast = 3, keepWithinDays = 7))
        val entries = (0 until 20).map { entry(now.minus(Duration.ofDays(it.toLong()))) }

        val result = policy.classify(entries, now)
        val combined = (result.keep + result.prune).map { it.metadata.timestamp }.toSet()
        val expected = entries.map { it.metadata.timestamp }.toSet()
        assertEquals(expected, combined)
    }

    // ---- Helper ----

    private fun entry(timestamp: Instant, name: String? = null): BackupEntry =
        BackupEntry(
            path = Path.of("/tmp/world-stub.tar.zst"),
            metadata = BackupMetadata(timestamp, name, ArchiveFormat.TAR_ZST),
            sizeBytes = 0L,
        )
}
