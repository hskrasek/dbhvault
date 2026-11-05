# CONTEXT.md

Glossary of terminology used in DBHVault. See `AGENTS.md` for architecture and commands.

## Project

- **DBHVault** — Server-only Fabric mod (Kotlin) providing scheduled and manual world backups for the Dashboarders Heaven server. Mod ID `dbhvault`.
- **DBH** — Dashboarders Heaven, the Minecraft server this mod is built for.
- **Server-only** — The mod never loads on a client. Enforced three ways: `"environment": "server"` in `fabric.mod.json`, a `DedicatedServerModInitializer` entrypoint, and no client source set / `net.minecraft.client.*` imports.
- **Compatibility range** — `>=26.1 <26.4`. Built against 26.3; one jar runs on 26.1–26.3, so avoid APIs that only exist in the newest version.
- **Unobfuscated (26.x)** — Minecraft 26.1+ ships without obfuscation: no mappings, no `remapJar`, and `implementation` rather than `modImplementation`.
- **FLK** — fabric-language-kotlin. Required at runtime; provides Kotlin and kotlinx-coroutines (not bundled). Kotlin `object` entrypoints need `"adapter": "kotlin"`.
- **Jar-in-jar (`include`)** — Loom mechanism bundling `zstd-jni`, `tomlkt`, `sentry`, `sentry-log4j2` inside the mod jar. Non-transitive, so each artifact is listed explicitly.
- **Sentry DSN** — Error-reporting endpoint injected at build time from the gitignored `local.properties` via resource templating (`${sentry_dsn}`).

## Backups

- **Backup** — A single archive of the world in the backup directory.
- **Backup directory** — Where archives are stored (`backupDirectory`, default `./backups`).
- **BackupOrchestrator** — Runs one backup: freeze the world, archive it, thaw, then apply retention.
- **BackupRequest** — Why a backup is happening: `Scheduled` (always unpinned) or `Manual(name?)` from `/vault backup [name]`.
- **BackupResult** — Outcome of an orchestrated backup.
- **BackupMetadata** — Timestamp, optional name and format parsed from a filename. `parse` returns null (never throws) on malformed names so stray files don't break listing.
- **BackupNaming** — Builds filenames: `world-<UTC timestamp>(--<name>)?.<ext>`, timestamp pattern `yyyy-MM-dd'T'HH-mm-ss'Z'`. Names are sanitized to `[A-Za-z0-9_-]`, which also neutralizes path traversal.
- **BackupRegistry** — Scans and lists backups on disk.
- **Pinned backup** — A backup with a name. It is never pruned and doesn't count toward `keepLast`.
- **Scheduled / unpinned backup** — A backup with no name. It is subject to retention.
- **Freeze / thaw** — `WorldFlush.freeze` flushes chunks to disk (`saveAllChunks`) and sets `noSave = true` on every level. The returned `FrozenToken.thaw()` re-enables saving. Must run on the server thread.
- **`noSave`** — Per-level flag disabling chunk saves during archiving. It doesn't block player data, stats or advancements, so files can still change mid-archive.
- **Effective format** — The archive format actually used after fallback. Filenames use this, not the configured format.

## Archiving

- **BackupArchiver** — Interface for writing an archive.
- **TarZstdArchiver** — Default archiver, producing `.tar.zst`. Entries copy exactly the byte count in the tar header (truncating growth, zero-padding shrinkage) so files changing mid-archive can't corrupt the tar.
- **ZipArchiver** — Fallback producing `.zip`, used when zstd-jni native initialization fails.
- **ArchiverFactory** — Chooses the effective format/archiver.
- **`ArchiveFormat`** — Enum: `TAR_ZST`, `ZIP`.
- **Compression level** — zstd level (`compression.level`, default 3).

## Retention

- **Retention** — Pruning of old unpinned backups after a successful archive. Failures are logged and never delete the new backup.
- **HybridRetention** — The policy: keep the larger of two pools, plus all pinned backups.
  - **`keepLast`** (default 24) — pool A, the newest N unpinned backups.
  - **`keepWithinDays`** (default 30) — pool B, unpinned backups within X days (inclusive cutoff).
- **RetentionPolicy / RetentionDecision** — Interface and result (`keep`, `prune`) of classifying entries.
- **Config provider** — `HybridRetention` takes a `() -> RetentionConfig` read at each prune, so runtime `/vault retention` edits apply to the next backup.

## Scheduling and idle-skip

- **BackupScheduler** — Coroutine loop that takes a scheduled backup every `intervalHours` (default 6). It re-reads `enabled` at tick time so `pause` takes effect immediately.
- **Clock** — Time abstraction for testability.
- **Idle-skip** — Skips scheduled backups when nobody has played for `afterIdleHours` (default 24) and nothing changed since the last backup.
- **IdleTracker** — Thread-safe record of the last player activity (`recordActivity`, called on both join and disconnect). `shouldSkipScheduled` decides the skip.
- **World-dirty rule** — Skip only if the last backup is newer than the last player activity. Otherwise take a fresh backup to capture the last session.
- **Edge-triggered** — The tracker only sees join/disconnect events, so the scheduler also checks the live player list. Anyone online means never idle.

## Commands and permissions

- **`/vault`** — Brigadier command tree, registered on `SERVER_STARTING`.
  - `backup [name]` — manual backup; a name pins it.
  - `list`, `info` — inspect backups.
  - `schedule` — enable, pause, or change the interval.
  - `retention` — change `keepLast` / `keepWithinDays`.
  - `idle` — configure idle-skip.
  - `config` — view or reload configuration.
- **`Permissions.check`** — Op-level gating: level 4 for mutating commands, level 2 for `list`/`info`. Fabric-permissions-api node constants are reserved hooks only, since no 26.1-compatible release exists.

## Configuration

- **`config/dbhvault.toml`** — TOML config parsed with **tomlkt**. Defaults live in `ConfigDefaults`.
- **Config** — Root data class: `schedule`, `retention`, `compression`, `notifications`, `backupDirectory`.
- **ConfigManager** — Loads and saves config. Values are clamped to the ranges the commands allow, and a malformed file is never overwritten.
- **DBHVaultRuntime** — Holds live wiring (scope, orchestrator, registry, scheduler, idle tracker, notifier) and current config. `applyConfig` updates, persists, and broadcasts a change. Callbacks read `runtime.config()` so edits apply without restart.

## Notifications

- **Notifier** — Always logs, then optionally sends chat messages, dispatched onto the server thread.
- **BroadcastScope** — `ALL_PLAYERS`, `OPS_ONLY`, `LOG_ONLY`.
- **`backupEvents` / `configEvents`** — Two notification lanes. Backup started/finished/failed defaults to `ALL_PLAYERS`. Config changes such as schedule paused or retention changed default to `OPS_ONLY`.
- **Messages** — Chat formatting helpers, including the green bold mod-name brand prefix.

## Observability

- **Telemetry** — Sentry init, shutdown and exception capture. Disabled when the DSN is blank (dev).
- **`SENTRY_AUTH_TOKEN`** — Environment variable enabling source-bundle upload on release builds.

## Build and release

- **`gradle.properties`** — Single source of truth for all versions. Never hardcode versions in `build.gradle.kts`.
- **Loom** — Fabric Loom Gradle plugin. Deliberately has no `runClient`.
- **`templateProps`** — Map of tokens (`${version}`, `${sentry_dsn}`) expanded in `fabric.mod.json` and `dbhvault.build.properties`.
- **Mixins** — Config `dbhvault.mixins.json` exists but registers none. Any added must be Java, with `compatibilityLevel` `JAVA_25`.
- **Release** — Bump `mod_version`, add a Keep a Changelog entry in `CHANGELOG.md`, and update the `depends.minecraft` range if it changed.
