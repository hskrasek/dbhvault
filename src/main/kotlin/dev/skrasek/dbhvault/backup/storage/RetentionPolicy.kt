package dev.skrasek.dbhvault.backup.storage

import java.time.Instant

/**
 * Result of applying a [RetentionPolicy] to a list of [BackupEntry]:
 *   - [keep]: entries that stay on disk
 *   - [prune]: entries the caller should delete
 *
 * Together, `keep + prune` equals the input — every entry is classified,
 * none are silently lost. Order is unspecified.
 */
data class RetentionDecision(
    val keep: List<BackupEntry>,
    val prune: List<BackupEntry>,
)

/**
 * Decides which backups to keep and which to prune given the current set
 * of entries on disk and the current time.
 *
 * Implementations:
 *  - MUST be pure functions of (entries, now) — no I/O.
 *  - MUST never include pinned (named) backups in `prune`. Manual named
 *    backups are sacred; the user explicitly tagged them.
 *  - MAY use [Instant] from any clock (system or fixed for tests).
 */
interface RetentionPolicy {
    fun classify(entries: List<BackupEntry>, now: Instant): RetentionDecision
}
