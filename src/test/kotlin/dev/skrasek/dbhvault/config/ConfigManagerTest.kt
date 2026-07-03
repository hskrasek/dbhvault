package dev.skrasek.dbhvault.config

import dev.skrasek.dbhvault.tempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigManagerTest {
    @Test
    fun `loadOrCreate writes default config when file does not exist`() {
        val dir = tempDir()
        val configPath = dir.resolve("dbhvault.toml")

        val manager = ConfigManager(configPath)
        val config = manager.loadOrCreate()

        assertTrue(configPath.toFile().exists(), "Default config file should be written")
        assertEquals(6, config.schedule.intervalHours)
        assertEquals(24, config.retention.keepLast)
        assertEquals(ArchiveFormat.TAR_ZST, config.compression.format)
    }

    @Test
    fun `loadOrCreate parses an existing config file`() {
        val dir = tempDir()
        val configPath = dir.resolve("dbhvault.toml")
        configPath.toFile().writeText(
            """
            backupDirectory = "/var/mc/backups"

            [schedule]
            enabled = false
            intervalHours = 12

            [schedule.idleSkip]
            enabled = false
            afterIdleHours = 48

            [retention]
            keepLast = 100
            keepWithinDays = 60

            [compression]
            format = "ZIP"
            level = 6

            [notifications]
            backupEvents = "OPS_ONLY"
            configEvents = "LOG_ONLY"
            """.trimIndent()
        )

        val config = ConfigManager(configPath).loadOrCreate()

        assertEquals("/var/mc/backups", config.backupDirectory)
        assertEquals(false, config.schedule.enabled)
        assertEquals(12, config.schedule.intervalHours)
        assertEquals(48, config.schedule.idleSkip.afterIdleHours)
        assertEquals(100, config.retention.keepLast)
        assertEquals(ArchiveFormat.ZIP, config.compression.format)
        assertEquals(BroadcastScope.OPS_ONLY, config.notifications.backupEvents)
    }

    @Test
    fun `loadOrCreate clamps out-of-range values from a hand-edited file`() {
        val dir = tempDir()
        val configPath = dir.resolve("dbhvault.toml")
        configPath.toFile().writeText(
            """
            [schedule]
            intervalHours = 0

            [retention]
            keepLast = -1

            [compression]
            level = 99
            """.trimIndent()
        )

        val config = ConfigManager(configPath).loadOrCreate()

        assertEquals(1, config.schedule.intervalHours, "intervalHours=0 would busy-spin the scheduler")
        assertEquals(0, config.retention.keepLast, "negative keepLast would crash retention")
        assertEquals(22, config.compression.level, "level above zstd max must clamp")
    }

    @Test
    fun `tryLoad returns null for a malformed file and leaves it untouched`() {
        val dir = tempDir()
        val configPath = dir.resolve("dbhvault.toml")
        val malformed = "this is [not valid toml"
        configPath.toFile().writeText(malformed)

        val result = ConfigManager(configPath).tryLoad()

        assertEquals(null, result, "malformed file must not silently become defaults")
        assertEquals(malformed, configPath.toFile().readText(), "the broken file must be left for the operator")
    }

    @Test
    fun `save round-trips through load`() {
        val dir = tempDir()
        val configPath = dir.resolve("dbhvault.toml")
        val manager = ConfigManager(configPath)
        val original = manager.loadOrCreate()

        val mutated = original.copy(
            schedule = original.schedule.copy(intervalHours = 12),
            retention = original.retention.copy(keepLast = 50),
        )
        manager.save(mutated)

        val reloaded = ConfigManager(configPath).loadOrCreate()
        assertEquals(12, reloaded.schedule.intervalHours)
        assertEquals(50, reloaded.retention.keepLast)
    }
}
