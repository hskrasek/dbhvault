package dev.skrasek.dbhvault.config

import kotlinx.serialization.encodeToString
import net.peanuuutz.tomlkt.Toml
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText

/**
 * Loads and saves [Config] from a TOML file at [configPath].
 *
 * Writes are atomic (temp file + ATOMIC_MOVE) so a crash mid-write
 * can't truncate the config.
 */
class ConfigManager(private val configPath: Path) {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)

    private val toml: Toml = Toml {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Like [tryLoad], but a parse failure falls back to defaults instead of
     * null — bootstrap must always produce a working config. The broken file
     * is left untouched on disk for the operator to fix.
     */
    fun loadOrCreate(): Config = tryLoad() ?: Config().also {
        logger.error("Using default config; fix or delete {} and run /vault config reload", configPath)
    }

    /**
     * Loads the config, writing defaults first if the file doesn't exist.
     * Returns null if the file exists but fails to parse — callers like
     * `/vault config reload` use this to refuse the reload rather than
     * silently replacing the operator's (mistyped) file with defaults.
     */
    fun tryLoad(): Config? {
        if (!configPath.toFile().exists()) {
            logger.info("Config file not found at {}, writing defaults.", configPath)

            configPath.createParentDirectories()
            configPath.toFile().writeText(DEFAULT_CONFIG_TOML)

            return Config()
        }

        return try {
            toml.decodeFromString(Config.serializer(), configPath.readText()).validated()
        } catch (e: Exception) {
            logger.error("Failed to parse config at $configPath", e)
            null
        }
    }

    /**
     * Clamps file-loaded values into the same ranges the `/vault` commands
     * enforce. A hand-edited `intervalHours = 0` would otherwise busy-spin the
     * scheduler, and a negative `keepLast` would crash retention after every
     * successful archive.
     */
    private fun Config.validated(): Config {
        val clamped = copy(
            schedule = schedule.copy(
                intervalHours = schedule.intervalHours.coerceIn(1, 168),
                idleSkip = schedule.idleSkip.copy(
                    afterIdleHours = schedule.idleSkip.afterIdleHours.coerceIn(1, 720),
                ),
            ),
            retention = retention.copy(
                keepLast = retention.keepLast.coerceIn(0, 10_000),
                keepWithinDays = retention.keepWithinDays.coerceIn(0, 3650),
            ),
            compression = compression.copy(level = compression.level.coerceIn(0, 22)),
        )
        if (clamped != this) {
            logger.warn("Config values out of range in {} were clamped: {} -> {}", configPath, this, clamped)
        }
        return clamped
    }

    fun save(config: Config) {
        configPath.createParentDirectories()

        val tmpConfigPath = configPath.resolveSibling("${configPath.fileName}.tmp")

        tmpConfigPath.toFile().writeText(toml.encodeToString(config))

        Files.move(tmpConfigPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
