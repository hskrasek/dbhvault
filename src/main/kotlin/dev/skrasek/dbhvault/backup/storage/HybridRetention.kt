package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.config.RetentionConfig
import java.time.Instant

/**
 * Hybrid retention policy: keep the **larger** of two pools, plus all pinned.
 *
 *   pool A (count-based): the newest [RetentionConfig.keepLast] entries
 *   pool B (age-based):   all entries whose timestamp is within
 *                         [RetentionConfig.keepWithinDays] of `now`
 *
 * The kept set is `max(|A|, |B|)` (which is also the union, since both pools
 * are prefixes of the newest-first sort). Pinned entries are *always* kept,
 * regardless of count or age, and they don't consume the keepLast quota
 * (i.e., the count-based pool is computed from the *unpinned* entries).
 *
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/backup/storage/HybridRetentionTest.kt`
 *
 * Boundary semantics specified by the test suite:
 *  - The age-based filter is **inclusive** of the cutoff. An entry whose
 *    timestamp is exactly `now - keepWithinDays.days` is kept (i.e., use
 *    `!timestamp.isBefore(cutoff)` rather than `timestamp.isAfter(cutoff)`).
 *  - Input order is irrelevant — implementations should sort internally.
 *  - `keep + prune` equals the input (every entry is classified).
 */
class HybridRetention(private val config: RetentionConfig) : RetentionPolicy {
    override fun classify(entries: List<BackupEntry>, now: Instant): RetentionDecision =
        TODO("Implement: union of newest-N and within-X-days pools, plus all pinned")
}
