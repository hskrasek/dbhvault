# Scheduled Backups with Retention — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement DBHVault's core feature — corruption-safe asynchronous world backups on a configurable interval schedule with hybrid retention (count + age), manual `/vault` slash commands, and idle-skip behavior to avoid pointless backups of an unchanged world.

**Architecture:** A single `BackupOrchestrator` coordinates a four-phase backup pipeline (flush → archive → register → prune) on a coroutine-based `SupervisorJob` scoped to server lifetime. World corruption is prevented by a main-thread `WorldFlush` step (`MinecraftServer.saveAll(suppressLog=true, flush=true, force=false)` then `world.savingDisabled = true`) before any IO begins. Archive format and retention are pluggable behind interfaces (`BackupArchiver`, `RetentionPolicy`) so future tiered retention or alternate formats slot in without rewriting the orchestrator.

**Tech Stack:** Kotlin 2.3.21, Fabric Loader 0.19.2, Fabric API 0.145.1+26.1, fabric-language-kotlin 1.13.11+kotlin.2.3.21, fabric-permissions-api 0.3.1, kotlinx-coroutines, tomlkt 0.3.7, zstd-jni 1.5.6-3, JUnit 5 + MockK + kotlinx-coroutines-test.

---

## File Structure

### Production code

```
src/main/kotlin/dev/skrasek/dbhvault/
├── DBHVault.kt                              # Entrypoint (existing) — extend to wire components
│
├── config/
│   ├── Config.kt                            # Root config data class + nested data classes
│   ├── ConfigManager.kt                     # Load/save TOML, defaults, hot-reload
│   └── ConfigDefaults.kt                    # Default values + bundled default file content
│
├── backup/
│   ├── BackupOrchestrator.kt                # Coordinates flush → archive → register → prune
│   ├── BackupRequest.kt                     # Sealed type: ScheduledRequest, ManualRequest(name?)
│   ├── BackupResult.kt                      # Sealed type: Success(meta), Skipped(reason), Failed(cause)
│   ├── WorldFlush.kt                        # Main-thread save + autosave-off / autosave-on
│   ├── BackupNaming.kt                      # Timestamped names with optional suffix
│   ├── BackupMetadata.kt                    # Parsed-from-filename type: timestamp, name?, pinned
│   │
│   ├── archive/
│   │   ├── BackupArchiver.kt                # Interface: archive(srcDir, destFile, level): Long
│   │   ├── TarZstdArchiver.kt               # tar.zst impl using zstd-jni
│   │   ├── ZipArchiver.kt                   # .zip Deflate impl using JDK
│   │   └── ArchiverFactory.kt               # Picks tar.zst, falls back to zip on zstd-jni init fail
│   │
│   └── storage/
│       ├── BackupRegistry.kt                # Scans backupDir, lists/parses, classifies pinned vs scheduled
│       ├── RetentionPolicy.kt               # Interface: select(toKeep, toPrune)
│       └── HybridRetention.kt               # keepLast OR keepWithinDays; never prunes pinned
│
├── schedule/
│   ├── BackupScheduler.kt                   # Coroutine-based interval scheduler
│   ├── IdleTracker.kt                       # Tracks lastPlayerActivity + worldDirtySinceLastBackup
│   └── Clock.kt                             # Injectable clock (production: Clock.systemUTC(); tests: fixed)
│
├── permissions/
│   └── Permissions.kt                       # Wrapper around fabric-permissions-api, op-level fallback
│
├── notify/
│   └── Notifier.kt                          # broadcastAll(event), broadcastOps(event), log(event)
│
└── command/
    ├── VaultCommand.kt                      # Registers /vault root + dispatches to subcommands
    ├── BackupSubcommand.kt                  # /vault backup [name]
    ├── ListSubcommand.kt                    # /vault list
    ├── InfoSubcommand.kt                    # /vault info
    ├── ScheduleSubcommand.kt                # /vault schedule pause|resume|interval <hours>
    ├── RetentionSubcommand.kt               # /vault retention keep-last <n> | keep-within-days <n>
    ├── IdleSubcommand.kt                    # /vault idle enable|disable|after-hours <n>
    └── ConfigSubcommand.kt                  # /vault config reload | show
```

### Resources

```
src/main/resources/
├── fabric.mod.json                          # Modify: add fabric-permissions-api and fabric-api to depends
└── data/dbhvault/                           # (none required for v1)
```

### Tests

```
src/test/kotlin/dev/skrasek/dbhvault/
├── config/
│   └── ConfigManagerTest.kt
├── backup/
│   ├── BackupNamingTest.kt
│   ├── BackupMetadataTest.kt
│   ├── archive/
│   │   ├── ZipArchiverTest.kt
│   │   └── ArchiverFactoryTest.kt
│   └── storage/
│       ├── BackupRegistryTest.kt
│       └── HybridRetentionTest.kt
├── schedule/
│   ├── BackupSchedulerTest.kt
│   └── IdleTrackerTest.kt
└── TestFixtures.kt                          # Shared: tempDir(), fixedClock(), fakeServer()
```

---

## Task 1: Add dependencies and verify build

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle.kts`
- Modify: `settings.gradle.kts`

- [x] **Step 1: Add new version pins to `gradle.properties`**

Append to `gradle.properties`:

```properties
# Permissions
fabric_permissions_version=0.3.1

# Compression
zstd_jni_version=1.5.6-3

# Config
tomlkt_version=0.3.7

# Coroutines + tests
kotlinx_coroutines_version=1.9.0
junit_version=5.10.2
mockk_version=1.13.10
```

- [x] **Step 2: Add Lucko's Maven to `settings.gradle.kts`**

Modify the `pluginManagement.repositories` block — but actually Lucko's Maven is for `dependencies`, not plugins. Instead, modify `build.gradle.kts`'s `repositories { }` block (Step 3 below).

- [x] **Step 3: Replace `build.gradle.kts` with the new version**

Replace the entire `build.gradle.kts` with:

```kotlin
plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    `maven-publish`
}

val modVersion: String by project
val mavenGroup: String by project
val archivesBaseName: String by project

val minecraftVersion: String by project
val loaderVersion: String by project
val fabricApiVersion: String by project
val fabricKotlinVersion: String by project
val fabricPermissionsVersion: String by project
val zstdJniVersion: String by project
val tomlktVersion: String by project
val kotlinxCoroutinesVersion: String by project
val junitVersion: String by project
val mockkVersion: String by project

version = modVersion
group = mavenGroup
base.archivesName.set(archivesBaseName)

repositories {
    mavenCentral()
    maven("https://maven.lucko.me/") { name = "Lucko" }
}

loom {
    mods {
        register("dbhvault") {
            sourceSet(sourceSets["main"])
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // https://github.com/FabricMC/fabric-language-kotlin
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    implementation("me.lucko:fabric-permissions-api:$fabricPermissionsVersion")
    implementation("com.github.luben:zstd-jni:$zstdJniVersion")
    implementation("net.peanuuutz.tomlkt:tomlkt:$tomlktVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    val projectName = project.name
    inputs.property("projectName", projectName)
    from("LICENSE") {
        rename { "${it}_$projectName" }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
    }
}
```

- [x] **Step 4: Verify dependency resolution**

Run: `./gradlew dependencies --configuration runtimeClasspath`
Expected: Resolves without errors. `fabric-permissions-api`, `zstd-jni`, `tomlkt`, `kotlinx-coroutines-core` all appear in the tree.

- [x] **Step 5: Verify project still compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Existing `DBHVault.kt` still compiles.

- [x] **Step 6: Commit**

```bash
git add gradle.properties build.gradle.kts
git commit -m "add deps: permissions-api, zstd-jni, tomlkt, junit, mockk"
```

---

## Task 2: Define the config data model

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/config/Config.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/config/ConfigDefaults.kt`

- [x] **Step 1: Create `Config.kt`**

```kotlin
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
```

- [x] **Step 2: Create `ConfigDefaults.kt`**

```kotlin
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
```

- [x] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/config/
git commit -m "add config data model"
```

---

## Task 3: Implement ConfigManager with TOML load/save

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/config/ConfigManager.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/config/ConfigManagerTest.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/TestFixtures.kt`

- [x] **Step 1: Create `TestFixtures.kt`**

```kotlin
package dev.skrasek.dbhvault

import java.nio.file.Files
import java.nio.file.Path

internal fun tempDir(prefix: String = "dbhvault-test-"): Path {
    val dir = Files.createTempDirectory(prefix)
    Runtime.getRuntime().addShutdownHook(Thread {
        dir.toFile().deleteRecursively()
    })
    return dir
}
```

- [x] **Step 2: Write the failing test**

Create `src/test/kotlin/dev/skrasek/dbhvault/config/ConfigManagerTest.kt`:

```kotlin
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
```

- [x] **Step 3: Run test, verify it fails**

Run: `./gradlew test --tests dev.skrasek.dbhvault.config.ConfigManagerTest`
Expected: COMPILATION ERROR — `ConfigManager` doesn't exist yet.

- [x] **Step 4: Implement `ConfigManager.kt`**

```kotlin
package dev.skrasek.dbhvault.config

import kotlinx.serialization.encodeToString
import net.peanuuutz.tomlkt.Toml
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class ConfigManager(private val configPath: Path) {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)

    private val toml = Toml {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun loadOrCreate(): Config {
        if (!configPath.exists()) {
            logger.info("Config not found at {}, writing defaults", configPath)
            configPath.createParentDirectories()
            configPath.toFile().writeText(DEFAULT_CONFIG_TOML)
            return Config()
        }
        return try {
            toml.decodeFromString(Config.serializer(), configPath.readText())
        } catch (e: Exception) {
            logger.error("Failed to parse config at $configPath; using defaults", e)
            Config()
        }
    }

    fun save(config: Config) {
        configPath.createParentDirectories()
        val tmp = configPath.resolveSibling("${configPath.fileName}.tmp")
        tmp.toFile().writeText(toml.encodeToString(config))
        Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
```

- [x] **Step 5: Run test, verify it passes**

Run: `./gradlew test --tests dev.skrasek.dbhvault.config.ConfigManagerTest`
Expected: 3 tests, 0 failures.

- [x] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/config/ConfigManager.kt \
        src/test/kotlin/dev/skrasek/dbhvault/config/ConfigManagerTest.kt \
        src/test/kotlin/dev/skrasek/dbhvault/TestFixtures.kt
git commit -m "add ConfigManager with TOML load/save and atomic writes"
```

---

## Task 4: Backup naming and metadata parsing

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/BackupNaming.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/BackupMetadata.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/backup/BackupNamingTest.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/backup/BackupMetadataTest.kt`

Backup files are named like `world-2026-05-09T03-00-00Z.tar.zst` (scheduled) or `world-2026-05-09T03-00-00Z--pre-update.tar.zst` (named/pinned). The `--<name>` suffix marks a pinned backup.

- [x] **Step 1: Write `BackupNamingTest.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BackupNamingTest {
    @Test
    fun `scheduled name has no suffix`() {
        val instant = Instant.parse("2026-05-09T03:00:00Z")
        assertEquals(
            "world-2026-05-09T03-00-00Z.tar.zst",
            BackupNaming.fileName(instant, name = null, format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `named backup includes double-dash suffix`() {
        val instant = Instant.parse("2026-05-09T03:00:00Z")
        assertEquals(
            "world-2026-05-09T03-00-00Z--pre-update.tar.zst",
            BackupNaming.fileName(instant, name = "pre-update", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `zip format uses zip extension`() {
        val instant = Instant.parse("2026-05-09T03:00:00Z")
        assertEquals(
            "world-2026-05-09T03-00-00Z.zip",
            BackupNaming.fileName(instant, name = null, format = ArchiveFormat.ZIP),
        )
    }

    @Test
    fun `name is sanitized to filesystem-safe characters`() {
        val instant = Instant.parse("2026-05-09T03:00:00Z")
        assertEquals(
            "world-2026-05-09T03-00-00Z--my-backup_v2.tar.zst",
            BackupNaming.fileName(instant, name = "my backup/v2", format = ArchiveFormat.TAR_ZST),
        )
    }
}
```

- [x] **Step 2: Run test, verify failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.BackupNamingTest`
Expected: COMPILATION ERROR.

- [x] **Step 3: Implement `BackupNaming.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object BackupNaming {
    private const val PREFIX = "world-"
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC)
    private val SAFE_NAME_REGEX = Regex("[^A-Za-z0-9_-]+")

    fun fileName(timestamp: Instant, name: String?, format: ArchiveFormat): String {
        val ts = TIMESTAMP_FORMAT.format(timestamp)
        val cleanName = name?.let { sanitizeName(it) }?.takeIf { it.isNotEmpty() }
        val nameSuffix = cleanName?.let { "--$it" } ?: ""
        val ext = when (format) {
            ArchiveFormat.TAR_ZST -> "tar.zst"
            ArchiveFormat.ZIP -> "zip"
        }
        return "$PREFIX$ts$nameSuffix.$ext"
    }

    fun sanitizeName(raw: String): String =
        raw.replace(SAFE_NAME_REGEX, "-").trim('-')
}
```

- [x] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.BackupNamingTest`
Expected: 4 tests, 0 failures.

- [x] **Step 5: Write `BackupMetadataTest.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class BackupMetadataTest {
    @Test
    fun `parses scheduled backup`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z.tar.zst")
        assertEquals(Instant.parse("2026-05-09T03:00:00Z"), meta?.timestamp)
        assertNull(meta?.name)
        assertEquals(false, meta?.isPinned)
        assertEquals(ArchiveFormat.TAR_ZST, meta?.format)
    }

    @Test
    fun `parses named backup as pinned`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z--pre-update.tar.zst")
        assertEquals(Instant.parse("2026-05-09T03:00:00Z"), meta?.timestamp)
        assertEquals("pre-update", meta?.name)
        assertEquals(true, meta?.isPinned)
    }

    @Test
    fun `parses zip extension`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z.zip")
        assertEquals(ArchiveFormat.ZIP, meta?.format)
    }

    @Test
    fun `returns null for unparseable name`() {
        assertNull(BackupMetadata.parse("random-file.txt"))
        assertNull(BackupMetadata.parse("world-not-a-date.tar.zst"))
    }
}
```

- [x] **Step 6: Run test, verify failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.BackupMetadataTest`
Expected: COMPILATION ERROR.

- [x] **Step 7: Implement `BackupMetadata.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class BackupMetadata(
    val timestamp: Instant,
    val name: String?,
    val format: ArchiveFormat,
) {
    val isPinned: Boolean get() = name != null

    companion object {
        private val PATTERN = Regex(
            """^world-(\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z)(?:--([A-Za-z0-9_-]+))?\.(tar\.zst|zip)$"""
        )
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC)

        fun parse(fileName: String): BackupMetadata? {
            val match = PATTERN.matchEntire(fileName) ?: return null
            val (tsStr, name, ext) = match.destructured
            val instant = try {
                Instant.from(TIMESTAMP_FORMAT.parse(tsStr))
            } catch (e: Exception) {
                return null
            }
            val format = when (ext) {
                "tar.zst" -> ArchiveFormat.TAR_ZST
                "zip" -> ArchiveFormat.ZIP
                else -> return null
            }
            return BackupMetadata(instant, name.ifEmpty { null }, format)
        }
    }
}
```

- [x] **Step 8: Run all backup tests**

Run: `./gradlew test --tests "dev.skrasek.dbhvault.backup.*"`
Expected: 8 tests, 0 failures.

- [x] **Step 9: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/backup/BackupNaming.kt \
        src/main/kotlin/dev/skrasek/dbhvault/backup/BackupMetadata.kt \
        src/test/kotlin/dev/skrasek/dbhvault/backup/BackupNamingTest.kt \
        src/test/kotlin/dev/skrasek/dbhvault/backup/BackupMetadataTest.kt
git commit -m "add backup naming and metadata parsing"
```

---

## Task 5: Define BackupArchiver interface and implement ZipArchiver

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/archive/BackupArchiver.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/archive/ZipArchiver.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/backup/archive/ZipArchiverTest.kt`

- [x] **Step 1: Create the interface**

`src/main/kotlin/dev/skrasek/dbhvault/backup/archive/BackupArchiver.kt`:

```kotlin
package dev.skrasek.dbhvault.backup.archive

import java.nio.file.Path

interface BackupArchiver {
    /**
     * Archives [sourceDir] to [destFile]. Returns the size in bytes of the resulting archive.
     * Implementations stream files; callers must ensure [sourceDir] is not being written
     * concurrently (callers should disable autosave before invoking).
     */
    fun archive(sourceDir: Path, destFile: Path, level: Int): Long
}
```

- [x] **Step 2: Write `ZipArchiverTest.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.archive

import dev.skrasek.dbhvault.tempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.zip.ZipFile

class ZipArchiverTest {
    @Test
    fun `archives a directory and round-trips contents`() {
        val src = tempDir("zip-src-")
        src.resolve("level.dat").toFile().writeText("level-data")
        src.resolve("region").toFile().mkdir()
        src.resolve("region/r.0.0.mca").toFile().writeText("region-data")

        val dest = tempDir("zip-dest-").resolve("out.zip")

        val size = ZipArchiver().archive(src, dest, level = 6)

        assertTrue(size > 0L)
        ZipFile(dest.toFile()).use { zip ->
            val entries = zip.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("level.dat", "region/r.0.0.mca"), entries)
            assertEquals("level-data", zip.getInputStream(zip.getEntry("level.dat")).bufferedReader().readText())
        }
    }

    @Test
    fun `empty directory produces a valid empty zip`() {
        val src = tempDir("zip-empty-src-")
        val dest = tempDir("zip-empty-dest-").resolve("out.zip")

        ZipArchiver().archive(src, dest, level = 6)

        ZipFile(dest.toFile()).use { zip ->
            assertEquals(0, zip.entries().toList().size)
        }
    }
}
```

- [x] **Step 3: Run, verify failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.archive.ZipArchiverTest`
Expected: COMPILATION ERROR.

- [x] **Step 4: Implement `ZipArchiver.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.archive

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.fileSize
import kotlin.io.path.relativeTo

class ZipArchiver : BackupArchiver {
    override fun archive(sourceDir: Path, destFile: Path, level: Int): Long {
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(destFile))).use { zip ->
            zip.setLevel(level.coerceIn(Deflater.NO_COMPRESSION, Deflater.BEST_COMPRESSION))
            Files.walk(sourceDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        val entryName = file.relativeTo(sourceDir).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
            }
        }
        return destFile.fileSize()
    }
}
```

- [x] **Step 5: Run test, verify pass**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.archive.ZipArchiverTest`
Expected: 2 tests, 0 failures.

- [x] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/backup/archive/BackupArchiver.kt \
        src/main/kotlin/dev/skrasek/dbhvault/backup/archive/ZipArchiver.kt \
        src/test/kotlin/dev/skrasek/dbhvault/backup/archive/ZipArchiverTest.kt
git commit -m "add BackupArchiver interface and ZipArchiver impl"
```

---

## Task 6: Implement TarZstdArchiver and ArchiverFactory

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/archive/TarZstdArchiver.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/archive/ArchiverFactory.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/backup/archive/ArchiverFactoryTest.kt`

zstd-jni only ships its own `ZstdOutputStream`; tar framing must be done by hand. The tar format ([USTAR](https://en.wikipedia.org/wiki/Tar_(computing)#File_format)) is simple: 512-byte header per file, name+size+checksum, then file content padded to 512-byte blocks.

- [x] **Step 1: Implement `TarZstdArchiver.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.archive

import com.github.luben.zstd.ZstdOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.relativeTo

class TarZstdArchiver : BackupArchiver {
    override fun archive(sourceDir: Path, destFile: Path, level: Int): Long {
        ZstdOutputStream(BufferedOutputStream(Files.newOutputStream(destFile))).use { zstd ->
            zstd.setLevel(level)
            writeTar(sourceDir, zstd)
        }
        return destFile.fileSize()
    }

    private fun writeTar(sourceDir: Path, out: OutputStream) {
        Files.walk(sourceDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    val name = file.relativeTo(sourceDir).toString().replace('\\', '/')
                    writeTarEntry(out, name, file)
                }
        }
        // Two empty 512-byte blocks mark end-of-archive.
        out.write(ByteArray(1024))
    }

    private fun writeTarEntry(out: OutputStream, name: String, file: Path) {
        val size = Files.size(file)
        val header = buildTarHeader(name, size)
        out.write(header)
        Files.newInputStream(file).use { it.copyTo(out) }
        // Pad content to 512-byte boundary.
        val padding = ((512 - (size % 512)) % 512).toInt()
        if (padding > 0) out.write(ByteArray(padding))
    }

    private fun buildTarHeader(name: String, size: Long): ByteArray {
        require(name.length <= 100) { "Tar entry name too long: $name" }
        val header = ByteArray(512)
        // 0..99: name
        writeStr(header, 0, 100, name)
        // 100..107: mode (octal "0000644 ")
        writeStr(header, 100, 8, "0000644 ")
        // 108..115: uid
        writeStr(header, 108, 8, "0000000 ")
        // 116..123: gid
        writeStr(header, 116, 8, "0000000 ")
        // 124..135: size in octal, 11 chars + space
        writeStr(header, 124, 12, size.toString(8).padStart(11, '0') + " ")
        // 136..147: mtime in octal
        val mtime = (System.currentTimeMillis() / 1000).toString(8).padStart(11, '0') + " "
        writeStr(header, 136, 12, mtime)
        // 148..155: checksum placeholder (8 spaces while computing)
        writeStr(header, 148, 8, "        ")
        // 156: typeflag '0' = regular file
        header[156] = '0'.code.toByte()
        // 257..262: magic "ustar"
        writeStr(header, 257, 6, "ustar ")
        // 263..264: version "00"
        writeStr(header, 263, 2, "00")
        // Compute checksum: sum of all bytes treating header as unsigned, written as 6-octal + null + space
        val sum = header.sumOf { it.toInt() and 0xff }
        val chk = sum.toString(8).padStart(6, '0') + "  "
        writeStr(header, 148, 8, chk)
        return header
    }

    private fun writeStr(buf: ByteArray, offset: Int, len: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        val n = minOf(bytes.size, len)
        System.arraycopy(bytes, 0, buf, offset, n)
        // Remaining bytes left as zero (or spaces if previously written).
    }
}
```

- [x] **Step 2: Write `ArchiverFactoryTest.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.archive

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiverFactoryTest {
    @Test
    fun `creates ZipArchiver for ZIP format`() {
        val archiver = ArchiverFactory.create(ArchiveFormat.ZIP)
        assertTrue(archiver is ZipArchiver)
    }

    @Test
    fun `creates TarZstdArchiver when zstd available`() {
        // zstd-jni is on the classpath for tests, so this should succeed.
        val archiver = ArchiverFactory.create(ArchiveFormat.TAR_ZST)
        assertTrue(archiver is TarZstdArchiver)
    }

    @Test
    fun `effectiveFormat returns requested format when zstd works`() {
        assertTrue(ArchiverFactory.effectiveFormat(ArchiveFormat.TAR_ZST) == ArchiveFormat.TAR_ZST)
        assertTrue(ArchiverFactory.effectiveFormat(ArchiveFormat.ZIP) == ArchiveFormat.ZIP)
    }
}
```

- [x] **Step 3: Implement `ArchiverFactory.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.archive

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.slf4j.LoggerFactory

object ArchiverFactory {
    private val logger = LoggerFactory.getLogger(ArchiverFactory::class.java)

    private val zstdWorks: Boolean by lazy {
        try {
            // Force native lib load by constructing a small ZstdOutputStream.
            com.github.luben.zstd.ZstdOutputStream(java.io.ByteArrayOutputStream()).close()
            true
        } catch (t: Throwable) {
            logger.warn("zstd-jni native init failed; .tar.zst will fall back to .zip", t)
            false
        }
    }

    fun create(requested: ArchiveFormat): BackupArchiver = when (effectiveFormat(requested)) {
        ArchiveFormat.TAR_ZST -> TarZstdArchiver()
        ArchiveFormat.ZIP -> ZipArchiver()
    }

    fun effectiveFormat(requested: ArchiveFormat): ArchiveFormat =
        if (requested == ArchiveFormat.TAR_ZST && !zstdWorks) ArchiveFormat.ZIP else requested
}
```

- [x] **Step 4: Run tests**

Run: `./gradlew test --tests "dev.skrasek.dbhvault.backup.archive.*"`
Expected: All tests pass.

- [x] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/backup/archive/TarZstdArchiver.kt \
        src/main/kotlin/dev/skrasek/dbhvault/backup/archive/ArchiverFactory.kt \
        src/test/kotlin/dev/skrasek/dbhvault/backup/archive/ArchiverFactoryTest.kt
git commit -m "add TarZstdArchiver and ArchiverFactory with zstd fallback"
```

---

## Task 7: Backup registry — scan, list, classify

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/storage/BackupRegistry.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/backup/storage/BackupRegistryTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.tempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BackupRegistryTest {
    @Test
    fun `lists scheduled and pinned backups in newest-first order`() {
        val dir = tempDir()
        // Create files (zero-byte placeholders are fine; we only parse names).
        listOf(
            "world-2026-05-09T03-00-00Z.tar.zst",
            "world-2026-05-08T03-00-00Z.tar.zst",
            "world-2026-05-07T03-00-00Z--pre-update.tar.zst",
            "random.txt", // ignored
        ).forEach { dir.resolve(it).toFile().createNewFile() }

        val registry = BackupRegistry(dir)
        val all = registry.list()

        assertEquals(3, all.size)
        assertEquals(Instant.parse("2026-05-09T03:00:00Z"), all[0].metadata.timestamp)
        assertEquals(Instant.parse("2026-05-08T03:00:00Z"), all[1].metadata.timestamp)
        assertEquals("pre-update", all[2].metadata.name)
    }

    @Test
    fun `partitions pinned and scheduled`() {
        val dir = tempDir()
        listOf(
            "world-2026-05-09T03-00-00Z.tar.zst",
            "world-2026-05-08T03-00-00Z.tar.zst",
            "world-2026-05-07T03-00-00Z--keep.tar.zst",
        ).forEach { dir.resolve(it).toFile().createNewFile() }

        val registry = BackupRegistry(dir)
        val pinned = registry.list().filter { it.metadata.isPinned }
        val scheduled = registry.list().filter { !it.metadata.isPinned }

        assertEquals(1, pinned.size)
        assertEquals(2, scheduled.size)
        assertEquals("keep", pinned.first().metadata.name)
    }

    @Test
    fun `mostRecent returns newest backup or null when empty`() {
        val dir = tempDir()
        assertEquals(null, BackupRegistry(dir).mostRecent())

        dir.resolve("world-2026-05-09T03-00-00Z.tar.zst").toFile().createNewFile()
        assertEquals(
            Instant.parse("2026-05-09T03:00:00Z"),
            BackupRegistry(dir).mostRecent()!!.metadata.timestamp,
        )
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.storage.BackupRegistryTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement `BackupRegistry.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.backup.BackupMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.name

data class BackupEntry(
    val path: Path,
    val metadata: BackupMetadata,
    val sizeBytes: Long,
)

class BackupRegistry(private val backupDir: Path) {
    fun list(): List<BackupEntry> {
        if (!Files.isDirectory(backupDir)) return emptyList()
        return Files.list(backupDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { path ->
                    val meta = BackupMetadata.parse(path.name) ?: return@map null
                    BackupEntry(path, meta, path.fileSize())
                }
                .filter { it != null }
                .map { it!! }
                .sorted(compareByDescending { it.metadata.timestamp })
                .toList()
        }
    }

    fun mostRecent(): BackupEntry? = list().firstOrNull()
}
```

- [ ] **Step 4: Verify pass**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.storage.BackupRegistryTest`
Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/backup/storage/BackupRegistry.kt \
        src/test/kotlin/dev/skrasek/dbhvault/backup/storage/BackupRegistryTest.kt
git commit -m "add BackupRegistry"
```

---

## Task 8: Hybrid retention policy

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/storage/RetentionPolicy.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/storage/HybridRetention.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/backup/storage/HybridRetentionTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.backup.BackupMetadata
import dev.skrasek.dbhvault.config.ArchiveFormat
import dev.skrasek.dbhvault.config.RetentionConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class HybridRetentionTest {
    private val now = Instant.parse("2026-05-09T00:00:00Z")
    private val policy = HybridRetention(RetentionConfig(keepLast = 3, keepWithinDays = 5))

    @Test
    fun `keeps newest N when N exceeds age window`() {
        // 10 backups, one per day going back; keepLast=3, keepWithinDays=5 → keep 5 (age window wins)
        val entries = (0 until 10).map { entry(now.minus(Duration.ofDays(it.toLong()))) }
        val result = policy.classify(entries, now)
        assertEquals(5, result.keep.size)
        assertEquals(5, result.prune.size)
    }

    @Test
    fun `keepLast wins when no backups fall in age window`() {
        // 4 backups all older than 5 days; keepLast=3 → keep 3 newest
        val entries = (10 until 14).map { entry(now.minus(Duration.ofDays(it.toLong()))) }
        val result = policy.classify(entries, now)
        assertEquals(3, result.keep.size)
        assertEquals(1, result.prune.size)
    }

    @Test
    fun `pinned backups are never pruned`() {
        val entries = listOf(
            entry(now.minus(Duration.ofDays(100)), name = "pre-update"),
            entry(now.minus(Duration.ofDays(0))),
            entry(now.minus(Duration.ofDays(1))),
            entry(now.minus(Duration.ofDays(2))),
            entry(now.minus(Duration.ofDays(3))),
            entry(now.minus(Duration.ofDays(4))),
        )
        val result = policy.classify(entries, now)
        assertTrue(result.keep.any { it.metadata.isPinned })
        assertTrue(result.prune.none { it.metadata.isPinned })
    }

    @Test
    fun `under-threshold keeps everything`() {
        val entries = listOf(entry(now), entry(now.minus(Duration.ofDays(1))))
        val result = policy.classify(entries, now)
        assertEquals(2, result.keep.size)
        assertEquals(0, result.prune.size)
    }

    private fun entry(timestamp: Instant, name: String? = null): BackupEntry =
        BackupEntry(
            path = Path.of("/tmp/world-stub.tar.zst"),
            metadata = BackupMetadata(timestamp, name, ArchiveFormat.TAR_ZST),
            sizeBytes = 0L,
        )
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.storage.HybridRetentionTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement `RetentionPolicy.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.storage

import java.time.Instant

data class RetentionDecision(
    val keep: List<BackupEntry>,
    val prune: List<BackupEntry>,
)

interface RetentionPolicy {
    fun classify(entries: List<BackupEntry>, now: Instant): RetentionDecision
}
```

- [ ] **Step 4: Implement `HybridRetention.kt`**

```kotlin
package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.config.RetentionConfig
import java.time.Duration
import java.time.Instant

class HybridRetention(private val config: RetentionConfig) : RetentionPolicy {
    override fun classify(entries: List<BackupEntry>, now: Instant): RetentionDecision {
        val (pinned, unpinned) = entries.partition { it.metadata.isPinned }
        val sortedNewestFirst = unpinned.sortedByDescending { it.metadata.timestamp }

        val cutoff = now.minus(Duration.ofDays(config.keepWithinDays.toLong()))
        val keepByAge = sortedNewestFirst.filter { it.metadata.timestamp.isAfter(cutoff) }
        val keepByCount = sortedNewestFirst.take(config.keepLast)
        // Hybrid: take whichever set is larger (i.e., union, since both are prefixes of the same sort).
        val keep = if (keepByAge.size >= keepByCount.size) keepByAge else keepByCount
        val prune = sortedNewestFirst - keep.toSet()

        return RetentionDecision(keep = keep + pinned, prune = prune)
    }
}
```

- [ ] **Step 5: Verify pass**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.storage.HybridRetentionTest`
Expected: 4 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/backup/storage/RetentionPolicy.kt \
        src/main/kotlin/dev/skrasek/dbhvault/backup/storage/HybridRetention.kt \
        src/test/kotlin/dev/skrasek/dbhvault/backup/storage/HybridRetentionTest.kt
git commit -m "add HybridRetention policy"
```

---

## Task 9: WorldFlush — main-thread save coordination

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/WorldFlush.kt`

This task has no automated test — it depends on `MinecraftServer`, which isn't trivially mockable. We'll smoke-test via `runServer` in Task 14. Use the public deobfuscated API per 26.1.

- [ ] **Step 1: Implement `WorldFlush.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Coordinates "freezing" the world for backup. Must be invoked on the server thread.
 *
 * Usage:
 *   val token = WorldFlush.freeze(server)
 *   try { archive(...) } finally { token.thaw() }
 *
 * `freeze` calls saveAll(suppressLog=true, flush=true, force=false) which forces region
 * files to flush to disk, then sets savingDisabled=true on every loaded ServerWorld so
 * mid-backup chunk saves don't race with the archive read.
 */
object WorldFlush {
    private val logger = LoggerFactory.getLogger(WorldFlush::class.java)

    class FrozenToken(private val server: MinecraftServer) {
        fun thaw() {
            server.execute {
                for (world in server.worlds) {
                    world.savingDisabled = false
                }
                logger.debug("Saving re-enabled on all worlds")
            }
        }
    }

    /**
     * Must be called on the server thread. Returns when worlds are flushed and frozen.
     * Caller owns the returned token and MUST call [FrozenToken.thaw] in a finally block.
     */
    fun freeze(server: MinecraftServer): FrozenToken {
        check(server.isOnThread) { "WorldFlush.freeze must be called on the server thread" }
        logger.debug("Flushing all worlds before backup")
        server.saveAll(/* suppressLog = */ true, /* flush = */ true, /* force = */ false)
        for (world in server.worlds) {
            world.savingDisabled = true
        }
        return FrozenToken(server)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. If a Mojang API symbol has been renamed in 26.1, update accordingly — `MinecraftServer.saveAll`, `MinecraftServer.worlds`, `ServerWorld.savingDisabled`, and `MinecraftServer.execute` are the symbols to verify.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/backup/WorldFlush.kt
git commit -m "add WorldFlush for main-thread save coordination"
```

---

## Task 10: BackupRequest, BackupResult, BackupOrchestrator

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/BackupRequest.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/BackupResult.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/backup/BackupOrchestrator.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/backup/BackupOrchestratorTest.kt`

- [ ] **Step 1: Implement `BackupRequest.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

sealed class BackupRequest {
    data object Scheduled : BackupRequest()
    data class Manual(val name: String?) : BackupRequest()
}
```

- [ ] **Step 2: Implement `BackupResult.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

sealed class BackupResult {
    data class Success(
        val file: Path,
        val sizeBytes: Long,
        val timestamp: Instant,
        val duration: Duration,
        val pinned: Boolean,
    ) : BackupResult()

    data class Skipped(val reason: SkipReason) : BackupResult()

    data class Failed(val cause: Throwable) : BackupResult()

    enum class SkipReason {
        ALREADY_RUNNING,
        WORLD_IDLE_AND_CLEAN,
        SCHEDULE_DISABLED,
    }
}
```

- [ ] **Step 3: Write `BackupOrchestratorTest.kt`**

This test mocks the heavy dependencies (`MinecraftServer`, archiver) and verifies the orchestrator's contract: serializes via the lock, applies retention after success, classifies skip reasons correctly.

```kotlin
package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.backup.archive.BackupArchiver
import dev.skrasek.dbhvault.backup.storage.BackupEntry
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.backup.storage.HybridRetention
import dev.skrasek.dbhvault.backup.storage.RetentionDecision
import dev.skrasek.dbhvault.config.Config
import dev.skrasek.dbhvault.config.RetentionConfig
import dev.skrasek.dbhvault.tempDir
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BackupOrchestratorTest {
    @Test
    fun `concurrent invocations skip with ALREADY_RUNNING`() = runTest {
        val (orchestrator, _) = newOrchestrator()
        // Acquire the internal lock by starting one backup that never resolves.
        // Simpler: invoke in tight loop; second must skip.
        val first = orchestrator.runIfFree(BackupRequest.Manual(null))
        val second = orchestrator.runIfFree(BackupRequest.Manual(null))

        // Exactly one of (first, second) is Success (or attempts), the other is Skipped(ALREADY_RUNNING)
        assertTrue(
            (first is BackupResult.Skipped && first.reason == BackupResult.SkipReason.ALREADY_RUNNING) ||
                (second is BackupResult.Skipped && second.reason == BackupResult.SkipReason.ALREADY_RUNNING)
        )
    }

    @Test
    fun `successful manual backup invokes archiver and applies retention`() = runTest {
        val (orchestrator, deps) = newOrchestrator()
        val result = orchestrator.runIfFree(BackupRequest.Manual("preflight"))

        assertTrue(result is BackupResult.Success)
        verify { deps.archiver.archive(any(), any(), any()) }
        verify { deps.registry.list() }
    }

    private data class TestDeps(
        val archiver: BackupArchiver,
        val registry: BackupRegistry,
    )

    private fun newOrchestrator(): Pair<BackupOrchestrator, TestDeps> {
        val srcWorld = tempDir("orch-world-")
        srcWorld.resolve("level.dat").toFile().writeText("x")
        val backupDir = tempDir("orch-backups-")

        val archiver = mockk<BackupArchiver>(relaxed = true) {
            every { archive(any(), any(), any()) } returns 1234L
        }
        val registry = mockk<BackupRegistry>(relaxed = true) {
            every { list() } returns emptyList<BackupEntry>()
        }
        val retention = HybridRetention(RetentionConfig(keepLast = 5, keepWithinDays = 30))
        val clock = Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC)

        val orchestrator = BackupOrchestrator(
            config = Config(),
            worldDir = srcWorld,
            backupDir = backupDir,
            archiver = archiver,
            registry = registry,
            retention = retention,
            clock = clock,
            freeze = { /* no-op test stub */ AutoCloseable { } },
            prune = { _ -> /* no-op */ },
        )
        return orchestrator to TestDeps(archiver, registry)
    }
}
```

- [ ] **Step 4: Verify test failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.BackupOrchestratorTest`
Expected: COMPILATION ERROR.

- [ ] **Step 5: Implement `BackupOrchestrator.kt`**

```kotlin
package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.backup.archive.BackupArchiver
import dev.skrasek.dbhvault.backup.storage.BackupEntry
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.backup.storage.RetentionPolicy
import dev.skrasek.dbhvault.config.Config
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class BackupOrchestrator(
    private val config: Config,
    private val worldDir: Path,
    private val backupDir: Path,
    private val archiver: BackupArchiver,
    private val registry: BackupRegistry,
    private val retention: RetentionPolicy,
    private val clock: Clock,
    /** Returns an AutoCloseable that thaws the world when closed. */
    private val freeze: () -> AutoCloseable,
    /** Deletes pruned backups. Called with the prune list after retention classification. */
    private val prune: (List<BackupEntry>) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(BackupOrchestrator::class.java)
    private val running = AtomicBoolean(false)

    /**
     * Attempts to run a backup. If one is already running, returns Skipped(ALREADY_RUNNING).
     * This method blocks the calling coroutine for the duration of the archive — call from
     * a coroutine on Dispatchers.IO.
     */
    fun runIfFree(request: BackupRequest): BackupResult {
        if (!running.compareAndSet(false, true)) {
            return BackupResult.Skipped(BackupResult.SkipReason.ALREADY_RUNNING)
        }
        return try {
            execute(request)
        } finally {
            running.set(false)
        }
    }

    private fun execute(request: BackupRequest): BackupResult {
        val started = clock.instant()
        val name = (request as? BackupRequest.Manual)?.name
        val pinned = name != null
        val fileName = BackupNaming.fileName(started, name, config.compression.format)
        val destFile = backupDir.resolve(fileName)
        backupDir.toFile().mkdirs()

        return try {
            val token = freeze()
            try {
                val sizeBytes = archiver.archive(worldDir, destFile, config.compression.level)
                val finished = clock.instant()
                val all = registry.list()
                val decision = retention.classify(all, finished)
                prune(decision.prune)
                BackupResult.Success(
                    file = destFile,
                    sizeBytes = sizeBytes,
                    timestamp = started,
                    duration = Duration.between(started, finished),
                    pinned = pinned,
                )
            } finally {
                runCatching { token.close() }.onFailure { logger.error("thaw failed", it) }
            }
        } catch (t: Throwable) {
            logger.error("Backup failed", t)
            runCatching { destFile.toFile().delete() }
            BackupResult.Failed(t)
        }
    }
}
```

- [ ] **Step 6: Verify pass**

Run: `./gradlew test --tests dev.skrasek.dbhvault.backup.BackupOrchestratorTest`
Expected: 2 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/backup/BackupRequest.kt \
        src/main/kotlin/dev/skrasek/dbhvault/backup/BackupResult.kt \
        src/main/kotlin/dev/skrasek/dbhvault/backup/BackupOrchestrator.kt \
        src/test/kotlin/dev/skrasek/dbhvault/backup/BackupOrchestratorTest.kt
git commit -m "add BackupOrchestrator with concurrency lock and retention pipeline"
```

---

## Task 11: IdleTracker

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/schedule/Clock.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/schedule/IdleTracker.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/schedule/IdleTrackerTest.kt`

The idle tracker decides whether a *scheduled* backup should be skipped. The rule:

> Skip a scheduled backup if all three are true:
> 1. Idle skip is enabled in config.
> 2. Zero players are currently online.
> 3. `now - lastPlayerActivity >= afterIdleHours`.
> 4. The most recent backup (if any) was taken AFTER `lastPlayerActivity`.
>
> Condition 4 ensures we always take ONE backup capturing the post-activity world state, then go quiet.

- [ ] **Step 1: Write the test**

```kotlin
package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.config.IdleSkipConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class IdleTrackerTest {
    private val cfg = IdleSkipConfig(enabled = true, afterIdleHours = 24)
    private val now = Instant.parse("2026-05-09T12:00:00Z")

    @Test
    fun `online player means not idle`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(7)))
        tracker.playerCountChanged(1, now)

        assertFalse(tracker.shouldSkipScheduled(cfg, lastBackup = null, now = now))
    }

    @Test
    fun `idle past threshold without prior backup is NOT skipped (capture last state)`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(2)))
        tracker.playerCountChanged(0, now.minus(Duration.ofDays(2)))

        assertFalse(tracker.shouldSkipScheduled(cfg, lastBackup = null, now = now))
    }

    @Test
    fun `idle past threshold with backup after last activity IS skipped`() {
        val lastActivity = now.minus(Duration.ofDays(2))
        val tracker = IdleTracker(initialActivity = lastActivity)
        tracker.playerCountChanged(0, lastActivity)
        val lastBackup = now.minus(Duration.ofDays(1)) // taken after activity ended

        assertTrue(tracker.shouldSkipScheduled(cfg, lastBackup = lastBackup, now = now))
    }

    @Test
    fun `idle but threshold not met is NOT skipped`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofHours(3)))
        tracker.playerCountChanged(0, now.minus(Duration.ofHours(3)))

        assertFalse(tracker.shouldSkipScheduled(cfg, lastBackup = null, now = now))
    }

    @Test
    fun `disabled idle skip never skips`() {
        val disabled = IdleSkipConfig(enabled = false, afterIdleHours = 1)
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(30)))
        tracker.playerCountChanged(0, now.minus(Duration.ofDays(30)))

        assertFalse(tracker.shouldSkipScheduled(disabled, lastBackup = now.minus(Duration.ofDays(1)), now = now))
    }

    @Test
    fun `player joining resets activity`() {
        val tracker = IdleTracker(initialActivity = now.minus(Duration.ofDays(30)))
        tracker.playerCountChanged(0, now.minus(Duration.ofDays(30)))
        tracker.playerCountChanged(1, now.minus(Duration.ofMinutes(5)))
        tracker.playerCountChanged(0, now.minus(Duration.ofMinutes(1)))

        assertEquals(now.minus(Duration.ofMinutes(1)), tracker.lastPlayerActivity)
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.schedule.IdleTrackerTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement `Clock.kt`**

```kotlin
package dev.skrasek.dbhvault.schedule

import java.time.Clock as JdkClock

/** Type alias for clarity. Test code injects Clock.fixed(...). */
typealias Clock = JdkClock
```

(This file is intentionally minimal; it documents that the project uses `java.time.Clock` for injectable time.)

- [ ] **Step 4: Implement `IdleTracker.kt`**

```kotlin
package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.config.IdleSkipConfig
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the most recent moment a player was on the server. Thread-safe.
 *
 * - When a player is online, [lastPlayerActivity] equals "now" at last update.
 * - When zero players are online, [lastPlayerActivity] freezes at the moment the last player left.
 *
 * Hook [playerCountChanged] from ServerPlayConnectionEvents.JOIN/DISCONNECT and a once-per-second
 * tick while at least one player is online (to keep activity fresh during long sessions).
 */
class IdleTracker(initialActivity: Instant) {
    private val lastActivity = AtomicReference(initialActivity)

    val lastPlayerActivity: Instant get() = lastActivity.get()

    /**
     * @param playerCount current online player count after the change
     * @param now timestamp of the change
     */
    fun playerCountChanged(playerCount: Int, now: Instant) {
        if (playerCount > 0) {
            lastActivity.set(now)
        }
        // When playerCount == 0, freeze the existing lastActivity (do not advance).
    }

    fun shouldSkipScheduled(
        config: IdleSkipConfig,
        lastBackup: Instant?,
        now: Instant,
    ): Boolean {
        if (!config.enabled) return false
        val activity = lastActivity.get()
        val idleFor = Duration.between(activity, now)
        if (idleFor < Duration.ofHours(config.afterIdleHours.toLong())) return false
        // Skip only if a backup has already been taken since the last activity.
        return lastBackup != null && lastBackup.isAfter(activity)
    }
}
```

- [ ] **Step 5: Verify pass**

Run: `./gradlew test --tests dev.skrasek.dbhvault.schedule.IdleTrackerTest`
Expected: 6 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/schedule/Clock.kt \
        src/main/kotlin/dev/skrasek/dbhvault/schedule/IdleTracker.kt \
        src/test/kotlin/dev/skrasek/dbhvault/schedule/IdleTrackerTest.kt
git commit -m "add IdleTracker with idle-skip rules"
```

---

## Task 12: BackupScheduler — coroutine-based interval scheduler

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/schedule/BackupScheduler.kt`
- Create: `src/test/kotlin/dev/skrasek/dbhvault/schedule/BackupSchedulerTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.backup.BackupRequest
import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.Config
import dev.skrasek.dbhvault.config.IdleSkipConfig
import dev.skrasek.dbhvault.config.ScheduleConfig
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSchedulerTest {
    @Test
    fun `fires backup at each interval boundary`() = runTest {
        val runner = mockk<suspend (BackupRequest) -> BackupResult>()
        coEvery { runner(any()) } returns BackupResult.Skipped(BackupResult.SkipReason.SCHEDULE_DISABLED)

        val scheduler = BackupScheduler(
            scheduleConfig = ScheduleConfig(enabled = true, intervalHours = 1, idleSkip = IdleSkipConfig(enabled = false)),
            shouldSkipIdle = { false },
            runBackup = runner,
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(3).toMillis())
        scheduler.stop()

        coVerify(atLeast = 3) { runner(BackupRequest.Scheduled) }
    }

    @Test
    fun `does not fire when schedule is disabled`() = runTest {
        val runner = mockk<suspend (BackupRequest) -> BackupResult>(relaxed = true)
        val scheduler = BackupScheduler(
            scheduleConfig = ScheduleConfig(enabled = false, intervalHours = 1, idleSkip = IdleSkipConfig(enabled = false)),
            shouldSkipIdle = { false },
            runBackup = runner,
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(3).toMillis())
        scheduler.stop()

        coVerify(exactly = 0) { runner(any()) }
    }

    @Test
    fun `respects idle skip predicate`() = runTest {
        val runner = mockk<suspend (BackupRequest) -> BackupResult>(relaxed = true)
        val scheduler = BackupScheduler(
            scheduleConfig = ScheduleConfig(enabled = true, intervalHours = 1, idleSkip = IdleSkipConfig(enabled = true, afterIdleHours = 1)),
            shouldSkipIdle = { true }, // always idle
            runBackup = runner,
        )

        scheduler.start(this)
        advanceTimeBy(Duration.ofHours(3).toMillis())
        scheduler.stop()

        coVerify(exactly = 0) { runner(any()) }
    }
}
```

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests dev.skrasek.dbhvault.schedule.BackupSchedulerTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement `BackupScheduler.kt`**

```kotlin
package dev.skrasek.dbhvault.schedule

import dev.skrasek.dbhvault.backup.BackupRequest
import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.ScheduleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class BackupScheduler(
    @Volatile private var scheduleConfig: ScheduleConfig,
    private val shouldSkipIdle: () -> Boolean,
    private val runBackup: suspend (BackupRequest) -> BackupResult,
) {
    private val logger = LoggerFactory.getLogger(BackupScheduler::class.java)
    private val jobRef = AtomicReference<Job?>(null)

    fun start(scope: CoroutineScope) {
        val newJob = scope.launch {
            while (isActive) {
                val cfg = scheduleConfig
                val intervalMs = Duration.ofHours(cfg.intervalHours.toLong()).toMillis()
                delay(intervalMs)
                if (!cfg.enabled) {
                    logger.debug("Schedule disabled; tick ignored")
                    continue
                }
                if (shouldSkipIdle()) {
                    logger.info("Skipping scheduled backup: world idle and clean since last backup")
                    continue
                }
                try {
                    runBackup(BackupRequest.Scheduled)
                } catch (t: Throwable) {
                    logger.error("Scheduled backup runner threw", t)
                }
            }
        }
        val previous = jobRef.getAndSet(newJob)
        previous?.cancel()
    }

    fun updateConfig(cfg: ScheduleConfig) {
        scheduleConfig = cfg
    }

    fun stop() {
        jobRef.getAndSet(null)?.cancel()
    }
}
```

- [ ] **Step 4: Verify pass**

Run: `./gradlew test --tests dev.skrasek.dbhvault.schedule.BackupSchedulerTest`
Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/schedule/BackupScheduler.kt \
        src/test/kotlin/dev/skrasek/dbhvault/schedule/BackupSchedulerTest.kt
git commit -m "add coroutine-based BackupScheduler with idle-skip"
```

---

## Task 13: Permissions wrapper

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/permissions/Permissions.kt`

This wraps `me.lucko.fabric.api.permissions.v0.Permissions.check(source, node, defaultOpLevel)`. The library already implements LuckPerms-or-fallback internally; we just centralize node names.

- [ ] **Step 1: Implement `Permissions.kt`**

```kotlin
package dev.skrasek.dbhvault.permissions

import me.lucko.fabric.api.permissions.v0.Permissions as FabricPermissions
import net.minecraft.server.command.ServerCommandSource

object Permissions {
    private const val ROOT = "dbhvault"

    object Node {
        const val BACKUP = "$ROOT.command.backup"
        const val LIST = "$ROOT.command.list"
        const val INFO = "$ROOT.command.info"
        const val SCHEDULE = "$ROOT.command.schedule"
        const val RETENTION = "$ROOT.command.retention"
        const val IDLE = "$ROOT.command.idle"
        const val CONFIG = "$ROOT.command.config"
    }

    /** Op level 4 fallback for mutating ops; level 2 for read-only. */
    fun check(source: ServerCommandSource, node: String, fallbackOp: Int = 4): Boolean =
        FabricPermissions.check(source, node, fallbackOp)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/permissions/Permissions.kt
git commit -m "add Permissions wrapper using fabric-permissions-api"
```

---

## Task 14: Notifier

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/notify/Notifier.kt`

- [ ] **Step 1: Implement `Notifier.kt`**

```kotlin
package dev.skrasek.dbhvault.notify

import dev.skrasek.dbhvault.config.BroadcastScope
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

class Notifier(private val server: MinecraftServer) {
    private val logger = LoggerFactory.getLogger(Notifier::class.java)

    fun send(scope: BroadcastScope, message: String) {
        // Always log.
        logger.info("[DBHVault] {}", message)
        if (scope == BroadcastScope.LOG_ONLY) return
        val text = Text.literal("[DBHVault] $message")
        server.execute {
            for (player in server.playerManager.playerList) {
                if (scope == BroadcastScope.OPS_ONLY && !server.playerManager.isOperator(player.gameProfile)) continue
                player.sendMessage(text, false)
            }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. If `playerManager.playerList`, `isOperator`, or `sendMessage` symbols differ in 26.1, adjust to match the deobfuscated names.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/notify/Notifier.kt
git commit -m "add Notifier with scope-based broadcast"
```

---

## Task 15: VaultCommand root + /vault info

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/VaultCommand.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/InfoSubcommand.kt`

We register `/vault` as a Brigadier root and dispatch to subcommand builders. Each subcommand is its own object exposing a `register(LiteralArgumentBuilder<ServerCommandSource>)` extension.

- [ ] **Step 1: Implement `VaultCommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource

object VaultCommand {
    fun register(runtime: DBHVaultRuntime) {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            register(dispatcher, runtime)
        }
    }

    private fun register(dispatcher: CommandDispatcher<ServerCommandSource>, runtime: DBHVaultRuntime) {
        val root: LiteralArgumentBuilder<ServerCommandSource> = CommandManager.literal("vault")
        BackupSubcommand.register(root, runtime)
        ListSubcommand.register(root, runtime)
        InfoSubcommand.register(root, runtime)
        ScheduleSubcommand.register(root, runtime)
        RetentionSubcommand.register(root, runtime)
        IdleSubcommand.register(root, runtime)
        ConfigSubcommand.register(root, runtime)
        dispatcher.register(root)
    }
}
```

`DBHVaultRuntime` is a holder we'll define in Task 21 — it bundles the orchestrator, scheduler, registry, config manager, and notifier so subcommands can pull what they need without globals.

- [ ] **Step 2: Implement `InfoSubcommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.time.Duration
import java.time.Instant

object InfoSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {
        root.then(
            CommandManager.literal("info")
                .requires { Permissions.check(it, Permissions.Node.INFO, fallbackOp = 2) }
                .executes { ctx ->
                    val cfg = runtime.config()
                    val recent = runtime.registry.mostRecent()
                    val recentLine = recent?.let { entry ->
                        val ago = Duration.between(entry.metadata.timestamp, Instant.now()).toHours()
                        "Last backup: ${entry.path.fileName} (${entry.sizeBytes / 1024 / 1024} MiB, ${ago}h ago)"
                    } ?: "Last backup: (none)"
                    val sched = if (cfg.schedule.enabled) "every ${cfg.schedule.intervalHours}h" else "disabled"
                    val ret = "keepLast=${cfg.retention.keepLast}, keepWithinDays=${cfg.retention.keepWithinDays}"
                    val idle = if (cfg.schedule.idleSkip.enabled) "after ${cfg.schedule.idleSkip.afterIdleHours}h idle" else "disabled"
                    ctx.source.sendFeedback({
                        Text.literal("DBHVault — schedule: $sched | retention: $ret | idle-skip: $idle\n$recentLine")
                    }, false)
                    1
                }
        )
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: COMPILATION ERROR — the other subcommand files don't exist yet, and `DBHVaultRuntime` doesn't exist. We'll add stubs in this commit and replace in later tasks.

- [ ] **Step 4: Add stubs for the other subcommands so compilation succeeds**

Create `src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt`:

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import net.minecraft.server.command.ServerCommandSource

internal object BackupSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {}
}
internal object ListSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {}
}
internal object ScheduleSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {}
}
internal object RetentionSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {}
}
internal object IdleSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {}
}
internal object ConfigSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {}
}
```

Stubs will be replaced in Tasks 16–20.

- [ ] **Step 5: Add `DBHVaultRuntime` placeholder**

Create `src/main/kotlin/dev/skrasek/dbhvault/DBHVaultRuntime.kt`:

```kotlin
package dev.skrasek.dbhvault

import dev.skrasek.dbhvault.backup.BackupOrchestrator
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.config.Config
import dev.skrasek.dbhvault.config.ConfigManager
import dev.skrasek.dbhvault.notify.Notifier
import dev.skrasek.dbhvault.schedule.BackupScheduler
import dev.skrasek.dbhvault.schedule.IdleTracker

class DBHVaultRuntime(
    val configManager: ConfigManager,
    val orchestrator: BackupOrchestrator,
    val registry: BackupRegistry,
    val scheduler: BackupScheduler,
    val idleTracker: IdleTracker,
    val notifier: Notifier,
    private val configRef: () -> Config,
) {
    fun config(): Config = configRef()
}
```

- [ ] **Step 6: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/command/VaultCommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/InfoSubcommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt \
        src/main/kotlin/dev/skrasek/dbhvault/DBHVaultRuntime.kt
git commit -m "add /vault command root, /vault info subcommand, runtime holder"
```

---

## Task 16: /vault backup [name] and /vault list

**Files:**
- Modify: `src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt` (delete `BackupSubcommand` and `ListSubcommand` stubs)
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/BackupSubcommand.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/ListSubcommand.kt`

- [ ] **Step 1: Delete the two stubs from `Stubs.kt`**

Edit `Stubs.kt` to remove `BackupSubcommand` and `ListSubcommand` blocks. Keep the others.

- [ ] **Step 2: Implement `BackupSubcommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.backup.BackupRequest
import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.permissions.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.time.Duration

internal object BackupSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {
        root.then(
            CommandManager.literal("backup")
                .requires { Permissions.check(it, Permissions.Node.BACKUP, fallbackOp = 4) }
                .executes { trigger(it.source, runtime, name = null) }
                .then(
                    CommandManager.argument("name", StringArgumentType.word())
                        .executes { trigger(it.source, runtime, StringArgumentType.getString(it, "name")) }
                )
        )
    }

    private fun trigger(source: ServerCommandSource, runtime: DBHVaultRuntime, name: String?): Int {
        source.sendFeedback({ Text.literal("Starting backup${if (name != null) " '$name'" else ""}...") }, false)
        runtime.scope.launch(Dispatchers.IO) {
            val result = runtime.orchestrator.runIfFree(BackupRequest.Manual(name))
            val msg = when (result) {
                is BackupResult.Success ->
                    "Backup complete: ${result.file.fileName} " +
                        "(${result.sizeBytes / 1024 / 1024} MiB in ${result.duration.toSeconds()}s)"
                is BackupResult.Skipped -> "Backup skipped: ${result.reason}"
                is BackupResult.Failed -> "Backup failed: ${result.cause.message}"
            }
            runtime.notifier.send(runtime.config().notifications.backupEvents, msg)
        }
        return 1
    }
}
```

This requires `DBHVaultRuntime` to expose a `scope: CoroutineScope`. Add it.

- [ ] **Step 3: Update `DBHVaultRuntime.kt` to expose the coroutine scope**

```kotlin
package dev.skrasek.dbhvault

import dev.skrasek.dbhvault.backup.BackupOrchestrator
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.config.Config
import dev.skrasek.dbhvault.config.ConfigManager
import dev.skrasek.dbhvault.notify.Notifier
import dev.skrasek.dbhvault.schedule.BackupScheduler
import dev.skrasek.dbhvault.schedule.IdleTracker
import kotlinx.coroutines.CoroutineScope

class DBHVaultRuntime(
    val scope: CoroutineScope,
    val configManager: ConfigManager,
    val orchestrator: BackupOrchestrator,
    val registry: BackupRegistry,
    val scheduler: BackupScheduler,
    val idleTracker: IdleTracker,
    val notifier: Notifier,
    private val configRef: () -> Config,
) {
    fun config(): Config = configRef()
}
```

- [ ] **Step 4: Implement `ListSubcommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

internal object ListSubcommand {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneOffset.UTC)

    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {
        root.then(
            CommandManager.literal("list")
                .requires { Permissions.check(it, Permissions.Node.LIST, fallbackOp = 2) }
                .executes { ctx ->
                    val entries = runtime.registry.list()
                    if (entries.isEmpty()) {
                        ctx.source.sendFeedback({ Text.literal("No backups found.") }, false)
                    } else {
                        val lines = buildString {
                            appendLine("DBHVault — ${entries.size} backup(s):")
                            for (e in entries) {
                                val pin = if (e.metadata.isPinned) " [pinned: ${e.metadata.name}]" else ""
                                val sizeMiB = e.sizeBytes / 1024 / 1024
                                appendLine("  ${FORMAT.format(e.metadata.timestamp)}  ${sizeMiB} MiB  ${e.path.fileName}$pin")
                            }
                        }.trimEnd()
                        ctx.source.sendFeedback({ Text.literal(lines) }, false)
                    }
                    1
                }
        )
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/command/BackupSubcommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/ListSubcommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt \
        src/main/kotlin/dev/skrasek/dbhvault/DBHVaultRuntime.kt
git commit -m "add /vault backup and /vault list subcommands"
```

---

## Task 17: /vault schedule

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/ScheduleSubcommand.kt`
- Modify: `src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt` (remove `ScheduleSubcommand` stub)

- [ ] **Step 1: Remove the `ScheduleSubcommand` stub from `Stubs.kt`**

- [ ] **Step 2: Implement `ScheduleSubcommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

internal object ScheduleSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {
        root.then(
            CommandManager.literal("schedule")
                .requires { Permissions.check(it, Permissions.Node.SCHEDULE, fallbackOp = 4) }
                .then(CommandManager.literal("pause").executes { setEnabled(it.source, runtime, enabled = false) })
                .then(CommandManager.literal("resume").executes { setEnabled(it.source, runtime, enabled = true) })
                .then(
                    CommandManager.literal("interval")
                        .then(
                            CommandManager.argument("hours", IntegerArgumentType.integer(1, 168))
                                .executes { setInterval(it.source, runtime, IntegerArgumentType.getInteger(it, "hours")) }
                        )
                )
        )
    }

    private fun setEnabled(source: ServerCommandSource, runtime: DBHVaultRuntime, enabled: Boolean): Int {
        val current = runtime.config()
        val next = current.copy(schedule = current.schedule.copy(enabled = enabled))
        runtime.applyConfig(next, "Schedule ${if (enabled) "resumed" else "paused"}", source)
        return 1
    }

    private fun setInterval(source: ServerCommandSource, runtime: DBHVaultRuntime, hours: Int): Int {
        val current = runtime.config()
        val next = current.copy(schedule = current.schedule.copy(intervalHours = hours))
        runtime.applyConfig(next, "Schedule interval set to ${hours}h", source)
        return 1
    }
}
```

This depends on `DBHVaultRuntime.applyConfig(next, message, source)` which doesn't exist yet. Add it.

- [ ] **Step 3: Add `applyConfig` to `DBHVaultRuntime.kt`**

Replace `DBHVaultRuntime.kt` with:

```kotlin
package dev.skrasek.dbhvault

import dev.skrasek.dbhvault.backup.BackupOrchestrator
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.config.Config
import dev.skrasek.dbhvault.config.ConfigManager
import dev.skrasek.dbhvault.notify.Notifier
import dev.skrasek.dbhvault.schedule.BackupScheduler
import dev.skrasek.dbhvault.schedule.IdleTracker
import kotlinx.coroutines.CoroutineScope
import net.minecraft.server.command.ServerCommandSource
import java.util.concurrent.atomic.AtomicReference

class DBHVaultRuntime(
    val scope: CoroutineScope,
    val configManager: ConfigManager,
    val orchestrator: BackupOrchestrator,
    val registry: BackupRegistry,
    val scheduler: BackupScheduler,
    val idleTracker: IdleTracker,
    val notifier: Notifier,
    initialConfig: Config,
) {
    private val configHolder = AtomicReference(initialConfig)

    fun config(): Config = configHolder.get()

    fun applyConfig(next: Config, summary: String, source: ServerCommandSource) {
        configHolder.set(next)
        configManager.save(next)
        scheduler.updateConfig(next.schedule)
        notifier.send(next.notifications.configEvents, "$summary (by ${source.name})")
    }
}
```

Note the constructor signature changed — `configRef: () -> Config` is replaced with `initialConfig: Config`. Update `Task 21` (wiring) accordingly.

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/command/ScheduleSubcommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt \
        src/main/kotlin/dev/skrasek/dbhvault/DBHVaultRuntime.kt
git commit -m "add /vault schedule subcommand and runtime applyConfig"
```

---

## Task 18: /vault retention and /vault idle

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/RetentionSubcommand.kt`
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/IdleSubcommand.kt`
- Modify: `src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt` (remove `RetentionSubcommand` and `IdleSubcommand` stubs)

- [ ] **Step 1: Remove both stubs from `Stubs.kt`**

- [ ] **Step 2: Implement `RetentionSubcommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource

internal object RetentionSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {
        root.then(
            CommandManager.literal("retention")
                .requires { Permissions.check(it, Permissions.Node.RETENTION, fallbackOp = 4) }
                .then(
                    CommandManager.literal("keep-last")
                        .then(
                            CommandManager.argument("count", IntegerArgumentType.integer(1, 10000))
                                .executes { ctx ->
                                    val n = IntegerArgumentType.getInteger(ctx, "count")
                                    val current = runtime.config()
                                    runtime.applyConfig(
                                        current.copy(retention = current.retention.copy(keepLast = n)),
                                        "Retention keepLast set to $n",
                                        ctx.source,
                                    )
                                    1
                                }
                        )
                )
                .then(
                    CommandManager.literal("keep-within-days")
                        .then(
                            CommandManager.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes { ctx ->
                                    val d = IntegerArgumentType.getInteger(ctx, "days")
                                    val current = runtime.config()
                                    runtime.applyConfig(
                                        current.copy(retention = current.retention.copy(keepWithinDays = d)),
                                        "Retention keepWithinDays set to $d",
                                        ctx.source,
                                    )
                                    1
                                }
                        )
                )
        )
    }
}
```

- [ ] **Step 3: Implement `IdleSubcommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource

internal object IdleSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {
        root.then(
            CommandManager.literal("idle")
                .requires { Permissions.check(it, Permissions.Node.IDLE, fallbackOp = 4) }
                .then(CommandManager.literal("enable").executes { setEnabled(it.source, runtime, true) })
                .then(CommandManager.literal("disable").executes { setEnabled(it.source, runtime, false) })
                .then(
                    CommandManager.literal("after-hours")
                        .then(
                            CommandManager.argument("hours", IntegerArgumentType.integer(1, 720))
                                .executes { ctx ->
                                    val h = IntegerArgumentType.getInteger(ctx, "hours")
                                    val cur = runtime.config()
                                    runtime.applyConfig(
                                        cur.copy(schedule = cur.schedule.copy(idleSkip = cur.schedule.idleSkip.copy(afterIdleHours = h))),
                                        "Idle-skip threshold set to ${h}h",
                                        ctx.source,
                                    )
                                    1
                                }
                        )
                )
        )
    }

    private fun setEnabled(source: ServerCommandSource, runtime: DBHVaultRuntime, enabled: Boolean): Int {
        val cur = runtime.config()
        runtime.applyConfig(
            cur.copy(schedule = cur.schedule.copy(idleSkip = cur.schedule.idleSkip.copy(enabled = enabled))),
            "Idle-skip ${if (enabled) "enabled" else "disabled"}",
            source,
        )
        return 1
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/command/RetentionSubcommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/IdleSubcommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt
git commit -m "add /vault retention and /vault idle subcommands"
```

---

## Task 19: /vault config

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/command/ConfigSubcommand.kt`
- Delete: `src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt` (now empty)

- [ ] **Step 1: Implement `ConfigSubcommand.kt`**

```kotlin
package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

internal object ConfigSubcommand {
    fun register(root: LiteralArgumentBuilder<ServerCommandSource>, runtime: DBHVaultRuntime) {
        root.then(
            CommandManager.literal("config")
                .requires { Permissions.check(it, Permissions.Node.CONFIG, fallbackOp = 4) }
                .then(
                    CommandManager.literal("reload")
                        .executes { ctx ->
                            val next = runtime.configManager.loadOrCreate()
                            runtime.applyConfig(next, "Config reloaded from disk", ctx.source)
                            1
                        }
                )
                .then(
                    CommandManager.literal("show")
                        .executes { ctx ->
                            val cfg = runtime.config()
                            ctx.source.sendFeedback({
                                Text.literal(
                                    "DBHVault config:\n" +
                                        "  backupDirectory=${cfg.backupDirectory}\n" +
                                        "  schedule.enabled=${cfg.schedule.enabled}\n" +
                                        "  schedule.intervalHours=${cfg.schedule.intervalHours}\n" +
                                        "  idleSkip.enabled=${cfg.schedule.idleSkip.enabled}\n" +
                                        "  idleSkip.afterIdleHours=${cfg.schedule.idleSkip.afterIdleHours}\n" +
                                        "  retention.keepLast=${cfg.retention.keepLast}\n" +
                                        "  retention.keepWithinDays=${cfg.retention.keepWithinDays}\n" +
                                        "  compression.format=${cfg.compression.format}\n" +
                                        "  compression.level=${cfg.compression.level}\n" +
                                        "  notifications.backupEvents=${cfg.notifications.backupEvents}\n" +
                                        "  notifications.configEvents=${cfg.notifications.configEvents}"
                                )
                            }, false)
                            1
                        }
                )
        )
    }
}
```

- [ ] **Step 2: Delete `Stubs.kt`**

```bash
trash src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/command/ConfigSubcommand.kt \
        src/main/kotlin/dev/skrasek/dbhvault/command/Stubs.kt
git commit -m "add /vault config subcommand"
```

(`Stubs.kt` will appear as a deletion in the staged diff because we trashed it.)

---

## Task 20: Wire everything in DBHVault.onInitializeServer

**Files:**
- Modify: `src/main/kotlin/dev/skrasek/dbhvault/DBHVault.kt`
- Modify: `src/main/resources/fabric.mod.json` (declare the additional `depends`)

- [ ] **Step 1: Update `fabric.mod.json` to declare new runtime deps**

Replace the existing `fabric.mod.json` with:

```json
{
  "schemaVersion": 1,
  "id": "dbhvault",
  "version": "${version}",
  "name": "DBHVault",
  "description": "Daily Backup, Heaven's Vault — server-side world backups for Dashboarders Heaven.",
  "authors": [
    "Hunter Skrasek"
  ],
  "contact": {
  },
  "license": "Apache-2.0",
  "icon": "assets/dbhvault/icon.png",
  "environment": "server",
  "entrypoints": {
    "server": [
      {
        "adapter": "kotlin",
        "value": "dev.skrasek.dbhvault.DBHVault"
      }
    ]
  },
  "mixins": [
    "dbhvault.mixins.json"
  ],
  "depends": {
    "fabricloader": ">=0.19.2",
    "minecraft": "~26.1",
    "java": ">=25",
    "fabric-api": "*",
    "fabric-language-kotlin": ">=1.13.11+kotlin.2.3.21",
    "fabric-permissions-api-v0": "*"
  }
}
```

- [ ] **Step 2: Replace `DBHVault.kt`**

```kotlin
package dev.skrasek.dbhvault

import dev.skrasek.dbhvault.backup.BackupOrchestrator
import dev.skrasek.dbhvault.backup.WorldFlush
import dev.skrasek.dbhvault.backup.archive.ArchiverFactory
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.backup.storage.HybridRetention
import dev.skrasek.dbhvault.command.VaultCommand
import dev.skrasek.dbhvault.config.ConfigManager
import dev.skrasek.dbhvault.notify.Notifier
import dev.skrasek.dbhvault.schedule.BackupScheduler
import dev.skrasek.dbhvault.schedule.IdleTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

object DBHVault : DedicatedServerModInitializer {
    const val MOD_ID = "dbhvault"
    private val logger = LoggerFactory.getLogger(MOD_ID)

    private val runtimeRef = AtomicReference<DBHVaultRuntime?>(null)
    private val scopeRef = AtomicReference<CoroutineScope?>(null)

    override fun onInitializeServer() {
        logger.info("Opening the vault for {}", MOD_ID)
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val runtime = bootstrap(server)
            runtimeRef.set(runtime)
            VaultCommand.register(runtime)
            wireIdleEvents(runtime)
            runtime.scheduler.start(runtime.scope)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            scopeRef.get()?.cancel()
            scopeRef.set(null)
            runtimeRef.set(null)
        }
    }

    private fun bootstrap(server: net.minecraft.server.MinecraftServer): DBHVaultRuntime {
        val configPath = Paths.get("config/dbhvault.toml")
        val configManager = ConfigManager(configPath)
        val cfg = configManager.loadOrCreate()

        val backupDir = Paths.get(cfg.backupDirectory)
        Files.createDirectories(backupDir)

        val worldDir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize()
        val archiver = ArchiverFactory.create(cfg.compression.format)
        val registry = BackupRegistry(backupDir)
        val retention = HybridRetention(cfg.retention)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopeRef.set(scope)
        val idleTracker = IdleTracker(initialActivity = Instant.now())
        val notifier = Notifier(server)

        val orchestrator = BackupOrchestrator(
            config = cfg,
            worldDir = worldDir,
            backupDir = backupDir,
            archiver = archiver,
            registry = registry,
            retention = retention,
            clock = Clock.systemUTC(),
            freeze = {
                val token = runOnServerThread(server) { WorldFlush.freeze(server) }
                AutoCloseable { token.thaw() }
            },
            prune = { entries ->
                for (e in entries) {
                    runCatching { e.path.toFile().delete() }
                }
            },
        )

        val scheduler = BackupScheduler(
            scheduleConfig = cfg.schedule,
            shouldSkipIdle = {
                val current = runtimeRef.get()?.config() ?: cfg
                idleTracker.shouldSkipScheduled(
                    config = current.schedule.idleSkip,
                    lastBackup = registry.mostRecent()?.metadata?.timestamp,
                    now = Instant.now(),
                )
            },
            runBackup = { request ->
                orchestrator.runIfFree(request).also { result ->
                    val current = runtimeRef.get()?.config() ?: cfg
                    val msg = describe(result)
                    notifier.send(current.notifications.backupEvents, msg)
                }
            },
        )

        return DBHVaultRuntime(
            scope = scope,
            configManager = configManager,
            orchestrator = orchestrator,
            registry = registry,
            scheduler = scheduler,
            idleTracker = idleTracker,
            notifier = notifier,
            initialConfig = cfg,
        )
    }

    private fun wireIdleEvents(runtime: DBHVaultRuntime) {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            runtime.idleTracker.playerCountChanged(server.playerManager.playerList.size, Instant.now())
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            runtime.idleTracker.playerCountChanged(server.playerManager.playerList.size, Instant.now())
        }
    }

    private fun describe(result: dev.skrasek.dbhvault.backup.BackupResult): String = when (result) {
        is dev.skrasek.dbhvault.backup.BackupResult.Success ->
            "Scheduled backup complete: ${result.file.fileName} (${result.sizeBytes / 1024 / 1024} MiB in ${result.duration.toSeconds()}s)"
        is dev.skrasek.dbhvault.backup.BackupResult.Skipped -> "Scheduled backup skipped: ${result.reason}"
        is dev.skrasek.dbhvault.backup.BackupResult.Failed -> "Scheduled backup failed: ${result.cause.message}"
    }

    /** Synchronously hops to the server thread to perform [block]. */
    private fun <T> runOnServerThread(server: net.minecraft.server.MinecraftServer, block: () -> T): T {
        if (server.isOnThread) return block()
        return server.submit<T>(block).get()
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. If `MinecraftServer.submit`, `MinecraftServer.getSavePath`, `WorldSavePath.ROOT`, or `ServerPlayConnectionEvents` differ in 26.1, adjust to match the deobfuscated names.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 5: Smoke test by launching the dev server**

Run: `./gradlew runServer`

Inside the server console:

```
op DevPlayer
/vault info
/vault backup smoke
/vault list
```

Expected:
- `/vault info` prints schedule + retention summary.
- `/vault backup smoke` prints "Starting backup 'smoke'..." and a success message ~5–30s later.
- `/vault list` shows `world-<timestamp>--smoke.tar.zst` with non-zero size.
- File exists at `run/backups/world-<timestamp>--smoke.tar.zst`.
- Server log shows `[DBHVault] Backup complete: ...` line.

If zstd-jni native init fails, the file extension will be `.zip` instead.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/DBHVault.kt \
        src/main/resources/fabric.mod.json
git commit -m "wire DBHVault: lifecycle, scheduler, commands, idle tracking"
```

---

## Self-Review Checklist (run by plan author before handoff)

**1. Spec coverage:**
- ✅ Scheduled backups every N hours (Task 12)
- ✅ Manual backup with optional name (Task 16)
- ✅ World corruption-safe via flush + savingDisabled (Task 9)
- ✅ Asynchronous via Dispatchers.IO + SupervisorJob (Task 20)
- ✅ Hybrid retention (Task 8)
- ✅ Pinned named backups never pruned (Task 8)
- ✅ Skip when running concurrently (Task 10)
- ✅ Idle skip with world-dirty check (Tasks 11, 12, 20)
- ✅ /vault command surface (Tasks 15–19)
- ✅ LuckPerms via fabric-permissions-api with op-level fallback (Task 13)
- ✅ TOML config (Tasks 2, 3)
- ✅ Backup-event broadcast scope split (Tasks 14, 16, 20)
- ✅ Fabric API integration: CommandRegistrationCallback, ServerLifecycleEvents, ServerPlayConnectionEvents
- ✅ tar.zst with .zip fallback (Task 6)

**2. Placeholder scan:** No "TBD", "TODO", or "implement later" found. The two notes about "verify these MC API names against 26.1 deobfuscated source" (Tasks 9, 14, 20) are real verification steps tied to a `compileKotlin` check, not placeholders.

**3. Type consistency:**
- `BackupArchiver.archive(sourceDir, destFile, level): Long` — consistent across Tasks 5, 6, 10.
- `BackupRegistry.list(): List<BackupEntry>` and `mostRecent(): BackupEntry?` — consistent in Tasks 7, 10, 15.
- `HybridRetention.classify(entries, now): RetentionDecision` — consistent in Tasks 8, 10.
- `IdleTracker.shouldSkipScheduled(config, lastBackup, now)` — consistent in Tasks 11, 20.
- `BackupScheduler` constructor takes `(scheduleConfig, shouldSkipIdle, runBackup)` — consistent in Tasks 12, 20.
- `DBHVaultRuntime` constructor revised in Task 17; the final shape is `(scope, configManager, orchestrator, registry, scheduler, idleTracker, notifier, initialConfig)` — used consistently in Task 20.

---

## Execution Notes

- **APIs to verify against 26.1 deobfuscated source** (any rename here means a one-line fix):
  `MinecraftServer.saveAll`, `ServerWorld.savingDisabled`, `MinecraftServer.worlds`,
  `MinecraftServer.execute`, `MinecraftServer.submit`, `MinecraftServer.isOnThread`,
  `MinecraftServer.getSavePath`, `WorldSavePath.ROOT`, `MinecraftServer.playerManager`,
  `PlayerManager.playerList`, `PlayerManager.isOperator`, `ServerPlayerEntity.sendMessage`,
  `ServerPlayConnectionEvents.JOIN/DISCONNECT`, `ServerLifecycleEvents.SERVER_STARTED/STOPPING`,
  `CommandRegistrationCallback.EVENT`, `CommandManager.literal/argument`,
  `ServerCommandSource.sendFeedback`.

- **First-run output:** the first launch will write `run/config/dbhvault.toml` with defaults and create `run/backups/`. Subsequent launches read the file as-is.

- **No restore in v1** by design — listing only, per spec interview answer.
