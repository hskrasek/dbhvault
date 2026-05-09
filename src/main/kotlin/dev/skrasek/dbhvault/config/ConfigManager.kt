package dev.skrasek.dbhvault.config

import java.nio.file.Path

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
    fun loadOrCreate(): Config = TODO("Implement: read TOML if present; otherwise write defaults")

    fun save(config: Config): Unit = TODO("Implement: encode to TOML and atomically replace configPath")
}
