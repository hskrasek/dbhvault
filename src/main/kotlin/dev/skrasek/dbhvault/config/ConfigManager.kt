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

    fun loadOrCreate(): Config {
        if (!configPath.toFile().exists()) {
            logger.info("Config file not found at {}, writing defaults.", configPath)

            configPath.createParentDirectories()
            configPath.toFile().writeText(DEFAULT_CONFIG_TOML)

            return Config()
        }

        return try {
            toml.decodeFromString(Config.serializer(), configPath.readText())
        } catch (e: Exception) {
            logger.error("Failed to parse config at $configPath; using defaults", e)

            return Config()
        }
    }

    fun save(config: Config) {
        configPath.createParentDirectories()

        val tmpConfigPath = configPath.resolveSibling("${configPath.fileName}.tmp")

        tmpConfigPath.toFile().writeText(toml.encodeToString(config))

        Files.move(tmpConfigPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
