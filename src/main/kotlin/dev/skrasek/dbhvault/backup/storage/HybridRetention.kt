package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.config.RetentionConfig
import java.time.Duration
import java.time.Instant

/**
 * Hybrid retention policy: keep the **larger** of two pools, plus all pinned.
 *
 *   pool A (count-based): the newest [RetentionConfig.keepLast] entries
 *   pool B (age-based):   all entries within [RetentionConfig.keepWithinDays]
 *                         of `now` (inclusive cutoff)
 *
 * The kept set is `max(|A|, |B|)` (which is also the union, since both pools
 * are prefixes of the newest-first sort). Pinned entries are *always* kept,
 * regardless of count or age, and they don't consume the keepLast quota
 * (the count-based pool is computed from the *unpinned* entries only).
 */
class HybridRetention(private val config: RetentionConfig) : RetentionPolicy {
    override fun classify(entries: List<BackupEntry>, now: Instant): RetentionDecision {
        val (pinned, unpinned) = entries.partition { it.metadata.isPinned }
        val sortedNewestFirst = unpinned.sortedByDescending { it.metadata.timestamp }

        val cutoff = now.minus(Duration.ofDays(config.keepWithinDays.toLong()))
        val keepByAge = sortedNewestFirst.filter { it.metadata.timestamp.isAfter(cutoff) || it.metadata.timestamp == cutoff }
        val keepByCount = sortedNewestFirst.take(config.keepLast)

        val keep = if (keepByAge.size >= keepByCount.size) keepByAge else keepByCount
        val prune = sortedNewestFirst - keep.toSet()

        return RetentionDecision(keep = keep + pinned, prune = prune)
    }
}
