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
 * Stub awaiting implementation. The companion test in
 * `src/test/kotlin/dev/skrasek/dbhvault/config/ConfigManagerTest.kt`
 * specifies the contract:
 *
 *  - [loadOrCreate] returns the parsed config when the file exists,
 *    or writes [DEFAULT_CONFIG_TOML] and returns the default `Config()`
 *    when it doesn't. Parse failures should fall back to defaults
 *    (and log) rather than crash the server.
 *  - [save] writes [config] atomically (temp file + atomic rename) so
 *    a crash mid-write can never leave the config truncated.
 *
 * Suggested deps already on the classpath: `net.peanuuutz.tomlkt.Toml`
 * (configured with `ignoreUnknownKeys = true`, `explicitNulls = false`)
 * for both decode and encode.
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
