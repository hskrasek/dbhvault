package dev.skrasek.dbhvault.config

internal const val DEFAULT_CONFIG_TOML = """# DBHVault configuration.
# Edit values here, or use /vault commands at runtime.
# Comments survive command-driven edits because tomlkt preserves them.

backupDirectory = "./backups"

[schedule]
enabled = true
intervalHours = 6

[schedule.idleSkip]
# When true, scheduled backups are skipped if zero players are online,
# the last player has been gone for at least afterIdleHours, AND a backup
# has already been taken since the last player activity.
# Manual /vault backup is unaffected.
enabled = true
afterIdleHours = 24

[retention]
# Hybrid: keep the last N backups OR all backups within X days, whichever
# pool is larger. Pinned (named) backups are never pruned.
keepLast = 24
keepWithinDays = 30

[compression]
# tar.zst is the default. Falls back to zip if zstd-jni native init fails.
format = "TAR_ZST"
level = 3

[notifications]
backupEvents = "ALL_PLAYERS"
configEvents = "OPS_ONLY"
"""
