package dev.skrasek.dbhvault.backup

/** What kind of backup the orchestrator is being asked to take. */
sealed class BackupRequest {
    /**
     * A backup triggered by the schedule. Always unpinned (subject to retention).
     */
    data object Scheduled : BackupRequest()

    /**
     * A backup triggered by `/vault backup [name]`.
     *
     * @property name optional pin name. When non-null, the resulting backup is
     *   pinned (never pruned) and its filename includes the sanitized name.
     *   When null, the backup is treated like a scheduled one (subject to retention).
     */
    data class Manual(val name: String?) : BackupRequest()
}
