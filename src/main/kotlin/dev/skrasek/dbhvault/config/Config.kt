package dev.skrasek.dbhvault.config

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val schedule: ScheduleConfig = ScheduleConfig(),
    val retention: RetentionConfig = RetentionConfig(),
    val compression: CompressionConfig = CompressionConfig(),
    val notifications: NotificationsConfig = NotificationsConfig(),
    val backupDirectory: String = "./backups",
)

@Serializable
data class ScheduleConfig(
    val enabled: Boolean = true,
    val intervalHours: Int = 6,
    val idleSkip: IdleSkipConfig = IdleSkipConfig(),
)

@Serializable
data class IdleSkipConfig(
    val enabled: Boolean = true,
    val afterIdleHours: Int = 24,
)

@Serializable
data class RetentionConfig(
    val keepLast: Int = 24,
    val keepWithinDays: Int = 30,
)

@Serializable
data class CompressionConfig(
    val format: ArchiveFormat = ArchiveFormat.TAR_ZST,
    val level: Int = 3,
)

@Serializable
enum class ArchiveFormat {
    TAR_ZST,
    ZIP,
}

@Serializable
data class NotificationsConfig(
    val backupEvents: BroadcastScope = BroadcastScope.ALL_PLAYERS,
    val configEvents: BroadcastScope = BroadcastScope.OPS_ONLY,
)

@Serializable
enum class BroadcastScope {
    ALL_PLAYERS,
    OPS_ONLY,
    LOG_ONLY,
}
