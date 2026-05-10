# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] — 2026-05-10

Adds Sentry SaaS observability so server-side errors and backup failures
surface to your Sentry project with mod, Minecraft, Fabric Loader, and
live backup-config context attached.

### Added

#### Telemetry

- New `Telemetry` facade in `dev.skrasek.dbhvault.observability` that
  delegates to a `TelemetrySink` interface. Default sink is a no-op, so
  the mod is fully functional with no Sentry configured. A Sentry-backed
  sink takes over iff a non-blank DSN is baked into
  `dbhvault.build.properties` at build time.
- Filtered Log4j2 `SentryAppender` attached to the `dev.skrasek.dbhvault`
  logger subtree — auto-captures in-mod ERROR-level logs as Sentry issues
  and INFO/WARN-level logs as breadcrumbs. Minecraft, Fabric, and other-mod
  loggers are not forwarded.
- Targeted captures at structured failure sites that don't flow through a
  logger: `BackupResult.Failed` in the entrypoint's `describe()`, and the
  IO scope's `CoroutineExceptionHandler`.
- Event context: `dbhvault@<mod_version>` release, `minecraft.version` and
  `fabric.loader.version` tags, and a `backup` context block reflecting the
  live archive format, retention, and schedule. Refreshed on `/vault config`
  edits so subsequent issues reflect the active config.
- PII posture: `isSendDefaultPii = false` and a `BeforeSendCallback` that
  strips `event.user`. Player names and UUIDs are never attached to events.

#### Build

- New `io.sentry.jvm.gradle` 6.6.0 plugin uploads a JVM source bundle on
  release builds, so Sentry stack traces show actual Kotlin source lines.
  Source upload only runs when `SENTRY_AUTH_TOKEN` is set in the environment.
- DSN is templated at build time from an untracked `local.properties` file
  (added to `.gitignore`); a committed `local.properties.example` documents
  the format. Dev `runServer` builds without the file produce a blank DSN
  and a no-op telemetry sink.

### Configuration

No `dbhvault.toml` schema changes. To enable Sentry on a release build:

1. Copy `local.properties.example` to `local.properties`.
2. Set `sentry.dsn=` to your Sentry project's public DSN (Settings →
   Client Keys (DSN)).
3. (Optional) Set `SENTRY_AUTH_TOKEN` in the environment for source-context
   upload.
4. Build: `./gradlew clean build`.

### Migration from 1.0.0

Drop-in upgrade. Existing `config/dbhvault.toml` files load unchanged.

## [1.0.0] — 2026-05-10

First production release. Server-side world backups for Minecraft 26.1+ Fabric servers.

### Added

#### Backup engine

- Asynchronous, corruption-safe world backups via a coroutine-based pipeline:
  flush worlds → freeze autosave → archive on `Dispatchers.IO` → thaw → apply
  retention. Never blocks the server thread.
- Two archive formats: `.tar.zst` (default, via `zstd-jni`) with graceful
  fallback to `.zip` when zstd-jni's native lib can't load on hardened or
  sandboxed hosts.
- Configurable compression level (zstd 1..22, or Deflate 0..9 for `.zip`).

#### Scheduling

- Hourly-precision interval scheduler running on a `SupervisorJob` coroutine
  scoped to server lifetime. Cancellation on `SERVER_STOPPING` is automatic.
- Idle-skip: scheduled backups are skipped when the world has been idle for
  N hours **AND** a backup has already captured the post-activity state.
  The "world dirty" rule guarantees the last player session is always
  preserved before going quiet.

#### Retention

- Hybrid policy: keep the larger of `keepLast` (count) or `keepWithinDays`
  (age, inclusive cutoff). Pinned (named) backups are never pruned and
  don't consume the count quota — operator-tagged snapshots survive
  forever regardless of how aggressive the retention is.

#### `/vault` slash commands

- `/vault info` — current schedule, retention, idle-skip, last backup
- `/vault list` — enumerate backups newest-first with size and timestamp
- `/vault backup [name]` — manual backup; named backups are pinned
- `/vault schedule pause | resume | interval <hours>` — runtime schedule control
- `/vault retention keep-last <n> | keep-within-days <n>` — retention tuning
- `/vault idle enable | disable | after-hours <n>` — idle-skip configuration
- `/vault config reload | show` — config inspection and hot reload

Mutating commands gate at op level 4; read-only commands (`info`, `list`)
gate at op level 2. Permission checks use 26.1's typed
`source.permissions().hasPermission(Permission)` API.

#### Configuration

- TOML config at `config/dbhvault.toml`, generated with conservative defaults
  on first run. Atomic writes (temp file + `ATOMIC_MOVE`) prevent truncation
  on crash. Operator-driven changes via `/vault` commands persist back to disk.

#### Notifications

- Per-event broadcast scope: backup events default to all players;
  config-change events default to ops only. Both can be set to log-only
  for headless deployments.

### Toolchain

- Minecraft 26.1+
- Fabric Loader 0.19.2+
- Fabric API 0.145.1+26.1+
- [Fabric Language Kotlin](https://github.com/FabricMC/fabric-language-kotlin) 1.13.11+kotlin.2.3.21+
- Java 25+

### Install

1. Drop `dbhvault-1.0.0.jar` into the server's `mods/` directory.
2. Also install Fabric API and Fabric Language Kotlin.
3. Start the server. On first boot, DBHVault writes `config/dbhvault.toml`
   with conservative defaults (6h schedule, 24+30d hybrid retention,
   tar.zst, all-players notifications).
4. As an op: `/vault info` to confirm registration, `/vault backup smoke`
   for a manual test backup.

[1.1.0]: https://github.com/hskrasek/dbhvault/releases/tag/v1.1.0
[1.0.0]: https://github.com/hskrasek/dbhvault/releases/tag/v1.0.0
