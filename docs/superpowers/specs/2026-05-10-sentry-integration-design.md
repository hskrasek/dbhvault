# Sentry Integration — Design Spec

**Status:** Approved for implementation planning
**Date:** 2026-05-10

## Goal

Surface DBHVault failures (and only DBHVault failures) to Sentry SaaS so that bugs in scheduled backups, archive operations, config loading, and command handlers can be diagnosed without tailing server logs. The mod stays usable with Sentry disabled — observability is additive, never load-bearing.

## Confirmed decisions

| Decision | Choice | Rationale |
| --- | --- | --- |
| Backend | Sentry SaaS (sentry.io) | Free tier covers expected volume; no infra to maintain. |
| Capture scope | Hybrid: targeted `captureException` calls + Log4j2 appender attached to the `dev.skrasek.dbhvault` logger subtree | `BackupResult.Failed` flows through the notifier, not a logger, so an appender alone misses the most important failure case. |
| DSN delivery | Baked into the release jar via Gradle `processResources` templating | Zero operator configuration; public DSNs are designed to be ingestion-only, so leak risk is bounded to event-quota abuse, acceptable for a private deployment. |
| DSN injection at build time | Untracked `local.properties` file (gitignored), with a committed `local.properties.example` template | Familiar Android-style pattern; dev `runServer` builds get a blank DSN and no-op cleanly. |
| Event context attached | Mod version (as Sentry release), Minecraft version, Fabric Loader version, current backup config (archive format, retention, schedule) | Enables release-tracking, version-correlation, and "works on my settings, not yours" diagnosis. |
| PII | None. Player names and UUIDs are explicitly *not* attached. A `BeforeSendCallback` strips the event `user` block defensively. | Server runs for real players; their identifiers are not relevant to mod debugging. |

## Architecture

### Component layout

One new package, one new Kotlin file, one new resource file.

```
src/main/kotlin/dev/skrasek/dbhvault/
└── observability/
    └── Telemetry.kt                # Public facade + internal sink interface

src/main/resources/
└── dbhvault.build.properties       # NEW — templated by Gradle (mod.version, sentry.dsn)

local.properties.example            # NEW — committed template; explains how to set DSN
```

`local.properties` itself is gitignored.

### `Telemetry` facade

```kotlin
object Telemetry {
    @JvmStatic fun init()
    @JvmStatic fun refreshConfigContext(config: Config)
    @JvmStatic fun captureException(t: Throwable)
    @JvmStatic fun captureBackupFailure(failure: BackupResult.Failed)
    @JvmStatic fun shutdown()
}
```

Internally backed by a `TelemetrySink` interface with two implementations:

- `NoOpSink` — default. Used when DSN is blank (dev builds) and in tests. Methods are no-ops.
- `SentrySink` — wraps `io.sentry.Sentry`. Installed by `init()` only if the templated DSN is non-blank.

`init()` is idempotent and self-contained: it reads `dbhvault.build.properties` from the classpath, decides whether to construct `SentrySink`, calls `Sentry.init { ... }`, attaches the filtered Log4j2 appender, and swaps the sink. Any exception thrown during `init()` is caught internally and logged at WARN — Sentry initialization failure must not prevent the mod from coming up.

`@JvmStatic` is used so Java callers (Mixins, Log4j2 bridges if needed later) can invoke methods without going through the `INSTANCE` field — consistent with the existing Kotlin/Java interop pattern documented in `CLAUDE.md`.

### Why a sink interface

- Keeps every public method a one-line delegation, no `if (initialized)` scattered through call sites.
- Provides a hermetic test seam: existing JUnit tests never call `init()`, so they hit `NoOpSink` and make zero network calls. No Sentry classes need to be on the test classpath in any code path tests exercise.
- A package-internal setter (`internal fun installSink(sink: TelemetrySink)`) lets a future test substitute a recording sink to assert "this code path captured an exception."

## Build wiring

### `build.gradle.kts` changes

Read `local.properties` at configuration time, fall back to empty:

```kotlin
val sentryDsn: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let {
        java.util.Properties()
            .apply { it.inputStream().use(::load) }
            .getProperty("sentry.dsn", "")
    }
    ?: ""

val sentryVersion = property("sentry_version") as String

dependencies {
    // ...existing entries
    implementation("io.sentry:sentry-log4j2:$sentryVersion")
}

tasks.processResources {
    val templateProps = mapOf(
        "version" to project.version,
        "sentry_dsn" to sentryDsn,
    )
    inputs.properties(templateProps)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") { expand(templateProps) }
    filesMatching("dbhvault.build.properties") { expand(templateProps) }
}
```

### `gradle.properties` addition

```
sentry_version=<latest stable at implementation time>
```

The implementer pins to a specific version (e.g., `8.0.0`) when wiring this up. Per the `CLAUDE.md` convention, version literals never appear in `build.gradle.kts` — only in `gradle.properties`.

### `dbhvault.build.properties` (new resource)

```
mod.version=${version}
sentry.dsn=${sentry_dsn}
```

### `local.properties.example` (new committed file)

```
# Copy to local.properties (which is gitignored) and set your DSN for release builds.
# Leave blank in dev to disable Sentry on `./gradlew runServer`.
sentry.dsn=
```

### `.gitignore` addition

```
local.properties
```

### Why not template MC and Fabric Loader versions

`Telemetry.init()` derives them at runtime from `SharedConstants.getCurrentVersion().name` and `FabricLoader.getInstance().getModContainer("fabricloader").get().metadata.version.friendlyString`. Threading them through Gradle is unnecessary indirection.

## Data flow

### Init order in `DBHVault.onInitializeServer()`

1. `Telemetry.init()` runs **first**, before `logger.info` and before any event registration. If the DSN is blank, this is essentially free; if it's set, the Log4j2 appender is attached so subsequent log statements at ERROR/WARN flow to Sentry.
2. Existing `logger.info`, `ServerLifecycleEvents` registration, and player listeners follow unchanged.
3. `SERVER_STOPPING` handler calls `Telemetry.shutdown()` after canceling the runtime scope, so any queued events get flushed before the server exits.

### Init order in `bootstrap(server)`

After `cfg = configManager.loadOrCreate()`:

```kotlin
Telemetry.refreshConfigContext(cfg)
```

This sets the global Sentry scope tags for the current backup configuration. Any error captured after this point is enriched with the active config.

### Config-change refresh

`DBHVaultRuntime.applyConfig` calls `Telemetry.refreshConfigContext(next)` after `configHolder.set(next)`, so subsequent errors reflect the new config rather than the bootstrap snapshot.

### Sentry global scope shape

```
Release: dbhvault@<mod_version>
Tags:
  minecraft.version
  fabric.loader.version
Context "backup":
  archive.format
  retention.keepLast
  retention.keepWithinDays
  schedule.enabled
  schedule.intervalHours
  schedule.idleSkip.enabled
```

`BeforeSendCallback`: strip `event.user` (defense-in-depth — we never set it, but Sentry's auto-collected user data passes through here too).

## Capture sites

### Targeted (the hybrid's intentional half)

1. **`DBHVault.kt:171` — `BackupResult.Failed` arm of `describe(result)`**: prepend `Telemetry.captureBackupFailure(result)` before the existing notifier formatting. The notifier message stays — Sentry doesn't replace ops-visible feedback.
2. **IO `CoroutineScope` in `DBHVault.kt:97`**: change
   ```kotlin
   CoroutineScope(SupervisorJob() + Dispatchers.IO)
   ```
   to
   ```kotlin
   CoroutineScope(
       SupervisorJob() +
       Dispatchers.IO +
       CoroutineExceptionHandler { _, t -> Telemetry.captureException(t) }
   )
   ```
   Without this handler, exceptions thrown inside coroutine bodies under a `SupervisorJob` flow to `Thread.uncaughtExceptionHandler`, which Minecraft owns and may swallow. **This is the most important capture site for the mod's actual failure modes.**
3. **`ConfigManager.loadOrCreate()`**: catch TOML parse errors and call `Telemetry.captureException(t)` with the file size as a tag (do *not* attach the file content — config files may include the backup directory path, which can be sensitive).

### Auto-capture (the hybrid's broad half)

Inside `Telemetry.init()`, after `Sentry.init { }`:

```kotlin
val ctx = LoggerContext.getContext(false)
val cfg = ctx.configuration
val appender = SentryAppender.createAppender(...).apply { start() }
cfg.addAppender(appender)
val loggerCfg = cfg.getLoggerConfig("dev.skrasek.dbhvault")
loggerCfg.addAppender(appender, Level.WARN, null)
ctx.updateLoggers()
```

The appender is attached to the `dev.skrasek.dbhvault` `LoggerConfig` and reaches its descendants via Log4j2's normal logger hierarchy (no programmatic name-prefix filter is needed — the hierarchy itself is the filter). Minecraft, Fabric, and other-mod loggers sit elsewhere in the tree and never reach this appender.

Sentry's `SentryAppender` translates Log4j2 events using two configurable thresholds:

- `minimumEventLevel` = `ERROR` — events at this level and above become Sentry issues.
- `minimumBreadcrumbLevel` = `INFO` — events at this level and above become breadcrumbs that attach to subsequent issues in the same scope.

Both are set explicitly when constructing the appender.

#### Logger-name audit prerequisite

Two existing call sites must use class-based logger names so the prefix filter catches them:

- `DBHVault.kt:34` uses `LoggerFactory.getLogger(MOD_ID)` (i.e., logger named `"dbhvault"`).

This needs to change to `LoggerFactory.getLogger(DBHVault::class.java)` (logger named `"dev.skrasek.dbhvault.DBHVault"`) before the appender filter will match it. Any other future logger creation should follow the same convention.

## Privacy and PII

- No player names, UUIDs, IPs, or chat content are attached to events.
- `Sentry.init { isSendDefaultPii = false }` (this is also the SDK default, but set explicitly for documentation value).
- `BeforeSendCallback` strips `event.user` even if something inadvertently sets it.
- Config-load failures attach the file *size* but not its contents (the backup directory path is a config field).

## Testing strategy

- Existing JUnit tests stay unchanged. They never call `Telemetry.init()`, so all telemetry calls hit `NoOpSink` and remain hermetic.
- New `TelemetryTest`:
  - Blank DSN → `init()` keeps `NoOpSink`; `captureException` is a no-op.
  - Non-blank fake DSN (`https://fake@localhost.invalid/0`) → `init()` swaps to `SentrySink`. Assert sink type and that `Sentry.isEnabled()` returns true. Do not assert HTTP went out (Sentry's transport queue is async; we'd be testing the SDK, not our code).
- New integration assertion in `BackupOrchestratorTest` (or a sibling test): inject a recording `TelemetrySink` via the package-internal setter, drive an orchestrator failure, assert the sink received exactly one `captureBackupFailure` with the expected `BackupResult.Failed` cause.

## Out of scope (intentional non-goals)

- **Performance/transaction tracing.** Sentry can record backup duration as a transaction. Backup duration is already in `BackupResult.Success.duration` and surfaced via the notifier; tracing adds cost without a clear question it answers.
- **Loom relocation/shading of `io.sentry.*`.** Acceptable for a single-deployment private mod. Document as future work if the mod is ever published or co-deployed with another mod that bundles Sentry — Loom's `relocate` (or a `shadow` task) can rewrite to `dev.skrasek.dbhvault.shadow.sentry.*`.
- **Runtime DSN override.** No env var or TOML config field. The DSN is fixed per release jar by deliberate choice.
- **Player operator attribution on `/vault` errors.** Excluded from scope per the PII decision.
- **Breadcrumbs for backup lifecycle events** (e.g., "scheduled backup started", "archive complete"). The Log4j2 appender already converts WARN-level mod logs to breadcrumbs; explicit lifecycle breadcrumbs can be added later if real failures show that error context isn't sufficient.

## Files modified or created

| File | Change |
| --- | --- |
| `src/main/kotlin/dev/skrasek/dbhvault/observability/Telemetry.kt` | New |
| `src/main/resources/dbhvault.build.properties` | New (templated) |
| `local.properties.example` | New (committed template) |
| `local.properties` | New (gitignored, operator-created) |
| `.gitignore` | Add `local.properties` |
| `gradle.properties` | Add `sentry_version=...` |
| `build.gradle.kts` | Read `local.properties`, add Sentry dependency, extend `processResources.expand` |
| `src/main/kotlin/dev/skrasek/dbhvault/DBHVault.kt` | Call `Telemetry.init`/`shutdown`/`refreshConfigContext`/`captureBackupFailure`; install `CoroutineExceptionHandler`; switch `LoggerFactory.getLogger(MOD_ID)` to class-based name |
| `src/main/kotlin/dev/skrasek/dbhvault/DBHVaultRuntime.kt` | Call `Telemetry.refreshConfigContext` in `applyConfig` |
| `src/main/kotlin/dev/skrasek/dbhvault/config/ConfigManager.kt` | Wrap parse path with `Telemetry.captureException` |
| `src/test/kotlin/dev/skrasek/dbhvault/observability/TelemetryTest.kt` | New |
| `src/test/kotlin/dev/skrasek/dbhvault/backup/BackupOrchestratorTest.kt` (or sibling) | New assertion via injected recording sink |

No changes to `dbhvault.toml` schema, `dbhvault.mixins.json`, `fabric.mod.json`, or `settings.gradle.kts`.
