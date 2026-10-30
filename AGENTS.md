# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Project

**DBHVault** — a server-only Fabric mod for Minecraft 26.1–26.3, written in Kotlin. Provides scheduled and manual world backups (with retention, idle-skip, and Sentry error reporting) for the Dashboarders Heaven server. Built against 26.3; the jar declares a `>=26.1 <26.4` compatibility range so one artifact loads on all three. See `CHANGELOG.md` for release history.

## Commands

| Task | Command |
| --- | --- |
| Run dev dedicated server | `./gradlew runServer` |
| Run unit tests (JUnit 5 + MockK) | `./gradlew test` |
| Build the mod jar | `./gradlew build` |
| Decompile Minecraft for IDE navigation | `./gradlew genSources` |
| Clean build outputs | `./gradlew clean` |

There is intentionally no `runClient` task — see "Server-only enforcement" below. The wrapper auto-downloads Gradle 9.4; JDK 25 must be selectable via `JAVA_HOME` or Gradle toolchain auto-provisioning.

## Architecture

### Source layout (`src/main/kotlin/dev/skrasek/dbhvault/`)

- `DBHVault.kt` — `DedicatedServerModInitializer` entrypoint. Bootstraps on `SERVER_STARTING` (builds config, orchestrator, scheduler, registers `/vault` on the live dispatcher), tears down on `SERVER_STOPPING`, and records player activity on join/disconnect.
- `DBHVaultRuntime.kt` — holds the live wiring (scope, orchestrator, registry, scheduler, idle tracker, notifier) and the current `Config`. `applyConfig` updates, persists, and broadcasts a config change. Callbacks read config via `runtime.config()` so runtime edits take effect without restart.
- `backup/` — `BackupOrchestrator` (freeze world, archive, then retention), `BackupRequest`/`BackupResult`, `BackupMetadata`, `BackupNaming`, `WorldFlush`.
  - `backup/archive/` — `BackupArchiver` with `TarZstdArchiver` (default) and `ZipArchiver` (fallback when zstd-jni native init fails); `ArchiverFactory` picks the effective format.
  - `backup/storage/` — `BackupRegistry` (scan/list backups on disk), `RetentionPolicy`, `HybridRetention` (keep last N *or* within X days; pinned backups never pruned).
- `command/` — `/vault` Brigadier tree: `VaultCommand` registers `backup`, `list`, `info`, `schedule`, `retention`, `idle`, `config` subcommands (one class each).
- `config/` — `Config`, `ConfigDefaults` (default TOML), `ConfigManager` (tomlkt; file at `config/dbhvault.toml`; values are clamped to command ranges; a malformed file is not overwritten).
- `schedule/` — `BackupScheduler`, `IdleTracker`, `Clock`.
- `notify/Notifier.kt` — chat broadcasts scoped by `ALL_PLAYERS` / `OPS_ONLY` etc.
- `observability/Telemetry.kt` — Sentry init/shutdown and exception capture.
- `permissions/Permissions.kt` — op-level checks (4 for mutating commands, 2 for `list`/`info`). fabric-permissions-api has no 26.1-compatible release, so the node constants are reserved hooks only.
- `util/` — `ByteFormat`, `Messages` (chat formatting).

Tests live in `src/test/kotlin/` and mirror the main package structure.

### Server-only enforcement (three layers)

The mod refuses to run on a client through three independent mechanisms — keep all three aligned when changing entrypoints or environment:

1. **`fabric.mod.json`** declares `"environment": "server"`, so Fabric Loader skips the mod on a logical client entirely.
2. **The entrypoint** `dev.skrasek.dbhvault.DBHVault` implements `DedicatedServerModInitializer` (not `ModInitializer`). Its `onInitializeServer()` only fires on a *dedicated* server — never on the integrated server inside single-player.
3. **`build.gradle.kts`** does *not* call `splitEnvironmentSourceSets()`, and the `loom { mods { } }` block only registers `sourceSets["main"]`. There is no `src/client/`.

When writing new code, do not import `net.minecraft.client.*`. Those classes resolve in the IDE because Loom's dev classpath includes them, but they crash on a dedicated server with `NoClassDefFoundError`.

### Kotlin entrypoint via fabric-language-kotlin

`DBHVault` is declared as a Kotlin `object` (singleton). The entrypoint in `fabric.mod.json` therefore uses `"adapter": "kotlin"` — without it, Loader would call a no-arg Java constructor and fail because Kotlin `object` only exposes a static `INSTANCE` field. Any new entrypoint declared as a Kotlin `object` must also set `"adapter": "kotlin"`.

`fabric-language-kotlin` is both a build dependency (`implementation` in `build.gradle.kts`) and a runtime dependency users must install (declared in `depends`). `kotlinx-coroutines-core` is deliberately *not* bundled because FLK provides it.

### Dependencies and jar-in-jar

`zstd-jni`, `tomlkt`, `sentry`, and `sentry-log4j2` are both `implementation` and `include` (Loom's `include` is non-transitive, so list each artifact explicitly). Do not bundle `log4j-*` — Minecraft supplies it.

### Mixins must be Java

The mixin config `dbhvault.mixins.json` exists (package `dev.skrasek.dbhvault.mixin`) but currently registers no mixins and there is no `src/main/java/` directory. If you add one, mixin classes must be Java under `src/main/java/dev/skrasek/dbhvault/mixin/` and listed in `dbhvault.mixins.json`; Kotlin files cannot be mixin targets. To call into Kotlin from a mixin, annotate Kotlin members with `@JvmStatic` on a Kotlin `object` or `companion object`.

The mixin config's `compatibilityLevel` is `JAVA_25` and must match the Java toolchain in `build.gradle.kts`.

### Minecraft 26.x specifics

26.1 is the first **unobfuscated** Minecraft release. Day-to-day consequences for editing the build:

- Mod dependencies use plain `implementation(...)` — *not* `modImplementation(...)`.
- There is no `mappings(...)` line; Mojang ships parameter names directly.
- The publishable artifact comes from `tasks.jar` — there is no `remapJar` step.
- Code must compile against 26.3 yet run on 26.1–26.3; avoid APIs that exist only in the newest version.

### Version pinning

`gradle.properties` is the single source of truth for every external version (Minecraft, Loader, Loom, Fabric API, Kotlin, fabric-language-kotlin, mod version, Sentry, test libraries). Plugin versions are threaded through `settings.gradle.kts`'s `pluginManagement` block so `build.gradle.kts`'s `plugins { }` carries no version literals. Bumping anything starts in `gradle.properties`; never hardcode a version in `build.gradle.kts`.

Kotlin is intentionally pinned to 2.3.21 so the single jar runs on 26.1's FLK (2.3.21) and newer FLK builds (backward-compatible). Bump only if dropping 26.1 support.

Releases: bump `mod_version`, add a `CHANGELOG.md` entry (Keep a Changelog format), and update the `fabric.mod.json` `depends.minecraft` range if the supported versions change.

### Resource templating and Sentry DSN

`tasks.processResources` expands `${version}` and `${sentry_dsn}` in `fabric.mod.json` and `dbhvault.build.properties`. No other tokens are expanded — extend the `templateProps` map in `build.gradle.kts` if you need more.

The Sentry DSN comes from the gitignored `local.properties` (copy `local.properties.example`); leave it blank in dev. `SENTRY_AUTH_TOKEN` in the environment enables source-bundle upload for release builds.

### Where to add code

- New mod logic → `src/main/kotlin/dev/skrasek/dbhvault/` (Kotlin), with tests under `src/test/kotlin/`.
- New `/vault` subcommands → a new class in `command/`, registered in `VaultCommand.registerDirect`, gated through `Permissions.check`.
- New mixin classes → Java only (see above) and register in `dbhvault.mixins.json`.
- New translation strings → `src/main/resources/assets/dbhvault/lang/en_us.json`.

### Renaming the mod ID

The string `dbhvault` is hardcoded in several places. Renaming requires touching all of them:

1. `fabric.mod.json` — `id`, `mixins[]`, `entrypoints.server[].value` package, `icon` path
2. `dbhvault.mixins.json` — filename and `package` field
3. `src/main/resources/assets/dbhvault/` — directory name
4. `build.gradle.kts` — `loom { mods { register("dbhvault") } }`, and the `dbhvault.build.properties` filename match in `processResources`
5. `gradle.properties` — `archives_base_name`
6. `DBHVault.MOD_ID`, the `config/dbhvault.toml` path in `DBHVault.bootstrap`, and the Sentry `projectName` in `build.gradle.kts`

Plus `settings.gradle.kts`'s `rootProject.name` and the Kotlin/Java package directories.
