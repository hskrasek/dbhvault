# Sentry Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Sentry SaaS into DBHVault so failures in scheduled backups, archive operations, and command handlers surface to Sentry with mod-version, MC-version, and backup-config context, while staying invisible in dev builds and during tests.

**Architecture:** A `Telemetry` Kotlin object delegates to a `TelemetrySink` interface (default `NoOpSink`, replaced by `SentrySink` after `init()` if a baked-in DSN is present). A Log4j2 `SentryAppender` is attached to the `dev.skrasek.dbhvault` logger subtree to auto-capture in-mod ERROR/WARN logs. The DSN is templated into `dbhvault.build.properties` at build time from an untracked `local.properties` file.

**Tech Stack:** Kotlin 2.3.21, Fabric Loader 0.19.2, Sentry Java SDK (`io.sentry:sentry-log4j2`), kotlinx-coroutines, JUnit 5 + MockK + kotlinx-coroutines-test.

**Spec:** [`docs/superpowers/specs/2026-05-10-sentry-integration-design.md`](../specs/2026-05-10-sentry-integration-design.md)

---

## Workflow split

Per the project's standing rule (test-writer role): **Claude writes the tests for tested phases; the user implements the production code.** Wiring phases with no tests are executed end-to-end by Claude.

| Task | Who implements | Why |
| --- | --- | --- |
| 1. Build wiring (DSN injection plumbing) | Claude end-to-end | Pure infrastructure, no unit tests |
| 2. Telemetry tests + compile stubs | Claude writes tests + stubs, hands off | Tested phase |
| 3. User implements `Telemetry` and `SentrySink` | **User** | Tested phase, per the standing rule |
| 4. Wire `Telemetry` into runtime | Claude end-to-end | Pure wiring (entrypoint, runtime, config-change hook) |
| 5. Smoke-test on `runServer` | Claude verification | Manual end-to-end check |

## Deviations from the spec

Two small simplifications, called out so they're not surprises during impl:

1. **Drop the explicit `Telemetry.captureException` call inside `ConfigManager.loadOrCreate`'s catch block.** The existing code already calls `logger.error("Failed to parse config at $configPath; using defaults", e)` on a logger named `dev.skrasek.dbhvault.config.ConfigManager`. The filtered Log4j2 appender catches that automatically. Adding a manual `captureException` would double-fire.
2. **Drop the "BackupOrchestrator integration assertion."** The orchestrator never calls `Telemetry` — the entrypoint's `describe()` does. The same coverage lives in `TelemetryTest` via a `RecordingSink` that asserts the contract (`captureBackupFailure` is invoked when the test calls it directly). Testing the `describe()` wiring would require extracting it to be testable; we skip that under YAGNI.

The spec's other capture sites stand: `describe()` for `BackupResult.Failed`, the IO scope's `CoroutineExceptionHandler`, plus the auto-capture appender.

---

## File Structure

### New files

```
src/main/kotlin/dev/skrasek/dbhvault/observability/
└── Telemetry.kt                          # Facade object, sink interface, NoOpSink, SentrySink

src/main/resources/
└── dbhvault.build.properties             # Templated by Gradle (mod.version, sentry.dsn)

src/test/kotlin/dev/skrasek/dbhvault/observability/
└── TelemetryTest.kt                      # JUnit tests + RecordingSink helper

local.properties.example                  # Committed: documents how to set DSN locally
```

### Modified files

```
.gitignore                                # Add `local.properties`
gradle.properties                         # Add `sentry_version`
build.gradle.kts                          # Read local.properties, add Sentry dep, extend processResources
src/main/kotlin/dev/skrasek/dbhvault/
├── DBHVault.kt                           # Logger name; init/shutdown; CoroutineExceptionHandler;
│                                         # refreshConfigContext; describe() captureBackupFailure
└── DBHVaultRuntime.kt                    # refreshConfigContext call in applyConfig
```

---

## Task 1: Build wiring for DSN injection

**Goal:** Make the build accept an optional DSN from an untracked `local.properties` file and bake it into a runtime resource. With no DSN configured, the build still succeeds and the resource contains a blank `sentry.dsn=`.

**Files:**
- Create: `local.properties.example`
- Modify: `.gitignore`
- Modify: `gradle.properties`
- Modify: `build.gradle.kts`
- Create: `src/main/resources/dbhvault.build.properties`

### Steps

- [ ] **Step 1: Create `local.properties.example`**

Create `/Users/hskrasek/Documents/Projects/Minecraft/dbhv-backups/local.properties.example` with:

```properties
# Copy this file to `local.properties` (gitignored) and set your DSN to enable
# Sentry reporting in release builds. Leave the value blank in dev to keep
# `./gradlew runServer` from emitting events to your Sentry project.
#
# Get the DSN from your Sentry project's Settings → Client Keys (DSN) page.
sentry.dsn=
```

- [ ] **Step 2: Add `local.properties` to `.gitignore`**

Append to the existing `.gitignore` (after the `# Local env` block):

```
# Sentry DSN injection — copy local.properties.example to local.properties
local.properties
```

- [ ] **Step 3: Pin Sentry SDK version in `gradle.properties`**

Add to `gradle.properties` under a new heading (place it near the existing `# Compression` / `# Config` headings to match the file's style):

```properties
# Observability
sentry_version=8.0.0
```

> If a newer stable `8.x.y` is available at impl time, prefer that; verify on https://central.sonatype.com/artifact/io.sentry/sentry. Stay on 8.x to avoid breaking-change surprises. Per the `CLAUDE.md` convention, the version literal lives only here, never in `build.gradle.kts`.

- [ ] **Step 4: Add the `sentry_version` accessor and Sentry dependency in `build.gradle.kts`**

In the existing `val ... = property(...) as String` block (after `val mockkVersion`), add:

```kotlin
val sentryVersion = property("sentry_version") as String
```

In the `dependencies { }` block, after the existing `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")` line, add:

```kotlin
implementation("io.sentry:sentry-log4j2:$sentryVersion")
```

- [ ] **Step 5: Read `local.properties` at configuration time**

Above the `tasks.processResources { ... }` block in `build.gradle.kts`, add:

```kotlin
val sentryDsn: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { file ->
        java.util.Properties()
            .apply { file.inputStream().use(::load) }
            .getProperty("sentry.dsn", "")
    }
    ?: ""
```

- [ ] **Step 6: Extend `processResources.expand` to template the DSN**

Replace the existing `tasks.processResources` block with:

```kotlin
tasks.processResources {
    val templateProps = mapOf(
        "version" to project.version,
        "sentry_dsn" to sentryDsn,
    )
    inputs.properties(templateProps)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(templateProps)
    }
    filesMatching("dbhvault.build.properties") {
        expand(templateProps)
    }
}
```

- [ ] **Step 7: Create the build-properties resource template**

Create `/Users/hskrasek/Documents/Projects/Minecraft/dbhv-backups/src/main/resources/dbhvault.build.properties` with:

```properties
mod.version=${version}
sentry.dsn=${sentry_dsn}
```

- [ ] **Step 8: Verify the build with no DSN configured**

Run: `./gradlew clean build -x test`

Expected: BUILD SUCCESSFUL. (Skipping tests for now; the test source set won't compile until Task 2's stubs land.)

Inspect the templated output:

```bash
unzip -p build/libs/dbhvault-1.0.0.jar dbhvault.build.properties
```

Expected output:
```
mod.version=1.0.0
sentry.dsn=
```

(The `sentry.dsn=` line is empty because no `local.properties` exists yet — that's the dev-build no-op path.)

- [ ] **Step 9: Commit**

```bash
git add .gitignore local.properties.example gradle.properties build.gradle.kts src/main/resources/dbhvault.build.properties
git commit -m "$(cat <<'EOF'
Task 1: build wiring for Sentry DSN injection

Adds the gradle.properties pin, local.properties.example template, and
processResources templating for dbhvault.build.properties. Build
succeeds with a blank DSN; release builds will inject the real DSN via
an untracked local.properties.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Telemetry tests and compile stubs (HAND-OFF)

**Goal:** Pin down the `Telemetry` public API contract by writing the tests first, plus the minimal stubs required for the test source set to compile. Hand off to the user for the actual implementation.

**Files:**
- Create: `src/main/kotlin/dev/skrasek/dbhvault/observability/Telemetry.kt` (stub only)
- Create: `src/test/kotlin/dev/skrasek/dbhvault/observability/TelemetryTest.kt`

### Steps

- [ ] **Step 1: Create the `Telemetry.kt` compile stub**

Create `/Users/hskrasek/Documents/Projects/Minecraft/dbhv-backups/src/main/kotlin/dev/skrasek/dbhvault/observability/Telemetry.kt` with the following — this is **stub-only**: every method body is `TODO("user implements...")`. The user will replace these bodies in Task 3.

```kotlin
package dev.skrasek.dbhvault.observability

import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.Config

/**
 * Sink-based facade for telemetry/error reporting.
 *
 * Default sink is [NoOpSink] so the mod is fully functional with no Sentry
 * configured and so test code remains hermetic without explicit mocking. Calling
 * [init] swaps the sink to [SentrySink] iff a non-blank DSN is present in the
 * `dbhvault.build.properties` resource.
 */
object Telemetry {
    @Volatile
    private var sink: TelemetrySink = NoOpSink

    /**
     * Initialize from the bundled `dbhvault.build.properties` resource. If the
     * `sentry.dsn` value is blank, this is a no-op (the sink stays [NoOpSink]).
     * Any internal failure must be caught and logged at WARN — telemetry init
     * never propagates an exception.
     */
    @JvmStatic
    fun init() {
        TODO("user implements: read build properties from classpath, " +
            "delegate to initInternal(dsn)")
    }

    /**
     * Test/internal seam: initialize with an explicit DSN. Used by tests to
     * exercise the blank-vs-non-blank branches without touching the
     * `dbhvault.build.properties` resource.
     */
    internal fun initInternal(dsn: String) {
        TODO("user implements: if dsn.isBlank() keep NoOpSink, else " +
            "construct SentrySink, call Sentry.init { ... }, attach Log4j2 " +
            "appender to dev.skrasek.dbhvault logger config, swap sink")
    }

    @JvmStatic
    fun refreshConfigContext(config: Config) = sink.refreshConfigContext(config)

    @JvmStatic
    fun captureException(t: Throwable) = sink.captureException(t)

    @JvmStatic
    fun captureBackupFailure(failure: BackupResult.Failed) = sink.captureBackupFailure(failure)

    @JvmStatic
    fun shutdown() = sink.shutdown()

    // ---- Test seam ----

    internal fun installSinkForTest(testSink: TelemetrySink) {
        sink = testSink
    }

    internal fun currentSinkForTest(): TelemetrySink = sink
}

/**
 * Sink contract. Implementations:
 *  - [NoOpSink]: default, used in dev builds and tests.
 *  - `SentrySink`: production, wraps `io.sentry.Sentry` (user implements in Task 3).
 */
interface TelemetrySink {
    fun refreshConfigContext(config: Config)
    fun captureException(t: Throwable)
    fun captureBackupFailure(failure: BackupResult.Failed)
    fun shutdown()
}

object NoOpSink : TelemetrySink {
    override fun refreshConfigContext(config: Config) {}
    override fun captureException(t: Throwable) {}
    override fun captureBackupFailure(failure: BackupResult.Failed) {}
    override fun shutdown() {}
}
```

- [ ] **Step 2: Create `TelemetryTest.kt`**

Create `/Users/hskrasek/Documents/Projects/Minecraft/dbhv-backups/src/test/kotlin/dev/skrasek/dbhvault/observability/TelemetryTest.kt` with:

```kotlin
package dev.skrasek.dbhvault.observability

import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.Config
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryTest {

    @AfterEach
    fun resetSink() {
        // Each test that swaps the sink must leave it back at NoOpSink so other
        // tests in the suite (which never call init()) stay hermetic.
        Telemetry.installSinkForTest(NoOpSink)
    }

    // ---- Default state ----

    @Test
    fun `default sink is NoOpSink`() {
        assertSame(NoOpSink, Telemetry.currentSinkForTest())
    }

    @Test
    fun `NoOpSink swallows captureException without throwing`() {
        // Sanity: calls before init() must never throw.
        Telemetry.captureException(RuntimeException("boom"))
        Telemetry.captureBackupFailure(BackupResult.Failed(RuntimeException("disk full")))
        Telemetry.refreshConfigContext(Config())
        Telemetry.shutdown()
    }

    // ---- Sink delegation contract ----

    @Test
    fun `captureException routes through current sink`() {
        val recording = RecordingSink()
        Telemetry.installSinkForTest(recording)

        val ex = RuntimeException("boom")
        Telemetry.captureException(ex)

        assertEquals(1, recording.exceptions.size)
        assertSame(ex, recording.exceptions.single())
    }

    @Test
    fun `captureBackupFailure routes through current sink`() {
        val recording = RecordingSink()
        Telemetry.installSinkForTest(recording)

        val failure = BackupResult.Failed(IllegalStateException("disk full"))
        Telemetry.captureBackupFailure(failure)

        assertEquals(1, recording.backupFailures.size)
        assertSame(failure, recording.backupFailures.single())
    }

    @Test
    fun `refreshConfigContext routes through current sink`() {
        val recording = RecordingSink()
        Telemetry.installSinkForTest(recording)

        val config = Config()
        Telemetry.refreshConfigContext(config)

        assertEquals(1, recording.configRefreshes.size)
        assertSame(config, recording.configRefreshes.single())
    }

    @Test
    fun `shutdown routes through current sink`() {
        val recording = RecordingSink()
        Telemetry.installSinkForTest(recording)

        Telemetry.shutdown()

        assertEquals(1, recording.shutdowns)
    }

    // ---- initInternal branching ----

    @Test
    fun `initInternal with blank DSN keeps NoOpSink`() {
        Telemetry.installSinkForTest(NoOpSink)
        Telemetry.initInternal(dsn = "")

        assertSame(NoOpSink, Telemetry.currentSinkForTest())
    }

    @Test
    fun `initInternal with whitespace DSN keeps NoOpSink`() {
        Telemetry.installSinkForTest(NoOpSink)
        Telemetry.initInternal(dsn = "   ")

        assertSame(NoOpSink, Telemetry.currentSinkForTest())
    }

    @Test
    fun `initInternal with non-blank DSN swaps to a non-NoOp sink`() {
        // The `.invalid` TLD (RFC 2606) guarantees DNS won't resolve, so even if
        // the SDK schedules a transport flush, no real network call lands.
        Telemetry.installSinkForTest(NoOpSink)
        Telemetry.initInternal(dsn = "https://fake@localhost.invalid/0")

        val current = Telemetry.currentSinkForTest()
        assertNotSame(NoOpSink, current)
        // Don't assert SentrySink type here — keeps the test resilient if the
        // implementer chooses a different concrete name.
        assertTrue(
            current::class.simpleName!!.contains("Sentry", ignoreCase = true),
            "expected sink class name to mention 'Sentry'; got ${current::class.simpleName}",
        )
    }

    @Test
    fun `initInternal failures must not propagate`() {
        // Pass a clearly-malformed DSN. The Sentry SDK is strict about DSN
        // shape; a hostname-less URL trips its validator. Telemetry.initInternal
        // must catch that internally — observability must not block startup.
        Telemetry.installSinkForTest(NoOpSink)
        Telemetry.initInternal(dsn = "not-a-real-dsn")
        // Reaching this line means no exception escaped. Sink may be NoOp or
        // a fail-soft variant; either is acceptable.
    }
}

/** Test double for asserting sink delegation. */
internal class RecordingSink : TelemetrySink {
    val exceptions = mutableListOf<Throwable>()
    val backupFailures = mutableListOf<BackupResult.Failed>()
    val configRefreshes = mutableListOf<Config>()
    var shutdowns: Int = 0
        private set

    override fun captureException(t: Throwable) {
        exceptions += t
    }

    override fun captureBackupFailure(failure: BackupResult.Failed) {
        backupFailures += failure
    }

    override fun refreshConfigContext(config: Config) {
        configRefreshes += config
    }

    override fun shutdown() {
        shutdowns += 1
    }
}
```

- [ ] **Step 3: Run the tests; verify they fail in the expected ways**

Run: `./gradlew test --tests "dev.skrasek.dbhvault.observability.TelemetryTest" --info`

Expected results:

| Test | Expected outcome before user's impl |
| --- | --- |
| `default sink is NoOpSink` | **PASS** (the stub initializes the field to `NoOpSink`) |
| `NoOpSink swallows captureException without throwing` | **PASS** (`NoOpSink` methods are bodyless `{}`) |
| `captureException routes through current sink` | **PASS** (delegation is wired in the stub) |
| `captureBackupFailure routes through current sink` | **PASS** (delegation wired) |
| `refreshConfigContext routes through current sink` | **PASS** (delegation wired) |
| `shutdown routes through current sink` | **PASS** (delegation wired) |
| `initInternal with blank DSN keeps NoOpSink` | **FAIL** with `NotImplementedError` |
| `initInternal with whitespace DSN keeps NoOpSink` | **FAIL** with `NotImplementedError` |
| `initInternal with non-blank DSN swaps to a non-NoOp sink` | **FAIL** with `NotImplementedError` |
| `initInternal failures must not propagate` | **FAIL** with `NotImplementedError` |

If any test in the upper "delegation" group fails, the stub is wrong — fix it before handing off. If the lower `initInternal` group passes, the stub is doing too much — also fix.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/observability/Telemetry.kt src/test/kotlin/dev/skrasek/dbhvault/observability/TelemetryTest.kt
git commit -m "$(cat <<'EOF'
Task 2: Telemetry tests and compile stubs

Stubs the Telemetry facade and TelemetrySink contract. Delegation paths
work with the default NoOpSink and a RecordingSink test double; the
init / initInternal methods are TODOs awaiting implementation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Hand off**

Output the following message and stop:

> Tests are written and currently failing as expected for the `initInternal` cases. They specify the contract. Over to you for the implementation. See **Task 3** below for the contract summary and acceptance criteria.

---

## Task 3: User implements `Telemetry`, `SentrySink`, and the appender wiring

> **Owner: User.** Claude does not write production code for this task.

**Goal:** Replace the `TODO(...)` bodies in `src/main/kotlin/dev/skrasek/dbhvault/observability/Telemetry.kt` with a working implementation, and add a `SentrySink` class that wraps `io.sentry.Sentry`. All `TelemetryTest` tests must pass.

### Contract summary (what the tests require)

1. `Telemetry.init()` reads `dbhvault.build.properties` from the classpath, parses it as a `java.util.Properties`, takes the `sentry.dsn` value (defaulting to empty), and delegates to `initInternal(dsn)`.
2. `Telemetry.initInternal(dsn)`:
   - If `dsn.isBlank()` → leave the sink as `NoOpSink` (do not touch `Sentry`).
   - Else → construct a `SentrySink`, which inside its constructor (or via a static factory) calls `Sentry.init { options -> options.dsn = dsn; options.isSendDefaultPii = false; options.beforeSend = ... }`, attaches the Log4j2 `SentryAppender` to the `dev.skrasek.dbhvault` `LoggerConfig`, and registers a `BeforeSendCallback` that strips `event.user`. Then swap `Telemetry.sink = SentrySink(...)`.
   - **Any internal exception must be caught and logged at WARN; init never propagates.**
3. `SentrySink` must implement all four `TelemetrySink` methods. The class name must contain "Sentry" (the test asserts `simpleName` contains "Sentry" case-insensitively).
4. `refreshConfigContext(config)` on `SentrySink` updates the global Sentry scope with:
   - Tags: `minecraft.version`, `fabric.loader.version` (read at sink construction via `SharedConstants.getCurrentVersion().name` and `FabricLoader.getInstance().getModContainer("fabricloader").get().metadata.version.friendlyString`).
   - Context block `backup`: `archive.format`, `retention.keepLast`, `retention.keepWithinDays`, `schedule.enabled`, `schedule.intervalHours`, `schedule.idleSkip.enabled`.
   - Release: `dbhvault@<mod_version>` (set once in init from `mod.version` in the build properties).
5. `captureException(t)` calls `Sentry.captureException(t)`.
6. `captureBackupFailure(failure)` calls `Sentry.captureException(failure.cause)`. **Optional but recommended:** set the event `fingerprint` to `["backup-failure"]` so backup failures group under a single Sentry issue regardless of stack-trace variance — implementer's discretion. Not required by any test.
7. `shutdown()` calls `Sentry.close()`.

### Log4j2 appender wiring sketch

Inside `SentrySink` construction, after `Sentry.init { ... }`:

```kotlin
import io.sentry.log4j2.SentryAppender
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LoggerContext

val ctx = LoggerContext.getContext(false)
val cfg = ctx.configuration
val appender = SentryAppender.createAppender(
    /* name = */ "Sentry",
    /* minimumBreadcrumbLevel = */ Level.INFO,
    /* minimumEventLevel = */ Level.ERROR,
    /* dsn = */ null,                    // already set via Sentry.init
    /* filter = */ null,
    /* layout = */ null,
).also { it.start() }
cfg.addAppender(appender)
val loggerCfg = cfg.getLoggerConfig("dev.skrasek.dbhvault")
loggerCfg.addAppender(appender, Level.WARN, null)
ctx.updateLoggers()
```

(Refer to the Sentry Java SDK docs for the current `SentryAppender.createAppender` signature — it has changed across major versions.)

### Acceptance

- `./gradlew test --tests "dev.skrasek.dbhvault.observability.TelemetryTest"` is fully green.
- `./gradlew test` (full suite) is fully green — no other tests regress.
- `./gradlew build` produces a jar.

When done, push to the branch and notify Claude to continue with Task 4.

---

## Task 4: Wire `Telemetry` into the runtime

**Goal:** Connect the now-implemented `Telemetry` to the entrypoint and runtime so it actually receives events. End-to-end Claude task: pure wiring, no new tests; the existing test suite must continue to pass.

**Files:**
- Modify: `src/main/kotlin/dev/skrasek/dbhvault/DBHVault.kt`
- Modify: `src/main/kotlin/dev/skrasek/dbhvault/DBHVaultRuntime.kt`

### Steps

- [ ] **Step 1: Switch `DBHVault`'s logger to a class-based name**

In `DBHVault.kt`, replace line 34:

```kotlin
private val logger = LoggerFactory.getLogger(MOD_ID)
```

with:

```kotlin
private val logger = LoggerFactory.getLogger(DBHVault::class.java)
```

> Why: the Log4j2 appender attaches to the `dev.skrasek.dbhvault` logger config. A logger named `"dbhvault"` lives under the *root* logger, not the `dev.skrasek.dbhvault` subtree, and would be missed by the appender filter.

- [ ] **Step 2: Add `Telemetry.init()` at the top of `onInitializeServer`**

Add the import:

```kotlin
import dev.skrasek.dbhvault.observability.Telemetry
```

Replace the `onInitializeServer` body's first statement so it reads:

```kotlin
override fun onInitializeServer() {
    Telemetry.init()
    logger.info("Opening the vault for {}", MOD_ID)
    // ...rest unchanged for now
```

- [ ] **Step 3: Add `Telemetry.shutdown()` to the `SERVER_STOPPING` handler**

Replace the existing `SERVER_STOPPING` registration with:

```kotlin
ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
    runtimeRef.getAndSet(null)?.let { runtime ->
        runtime.scheduler.stop()
        runtime.scope.cancel()
    }
    Telemetry.shutdown()
}
```

- [ ] **Step 4: Install a `CoroutineExceptionHandler` on the IO scope**

Add the import:

```kotlin
import kotlinx.coroutines.CoroutineExceptionHandler
```

In `bootstrap(server)`, replace the `scope` construction line:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

with:

```kotlin
val scope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, t -> Telemetry.captureException(t) },
)
```

> Why: `SupervisorJob` keeps sibling coroutines alive when one throws, but the failed coroutine's exception goes to the handler if present, otherwise to `Thread.uncaughtExceptionHandler` — which Minecraft owns and may swallow. Without this handler, scheduled-backup-coroutine failures would vanish from Sentry.

- [ ] **Step 5: Call `Telemetry.refreshConfigContext` after the initial config load**

In `bootstrap(server)`, add this line *immediately after* `val cfg = configManager.loadOrCreate()`:

```kotlin
Telemetry.refreshConfigContext(cfg)
```

- [ ] **Step 6: Capture `BackupResult.Failed` in `describe()`**

Replace the existing `describe` function (the `BackupResult.Failed` arm currently just formats a message) with:

```kotlin
private fun describe(result: BackupResult): String = when (result) {
    is BackupResult.Success ->
        "Backup complete: ${result.file.fileName} " +
            "(${result.sizeBytes / 1024 / 1024} MiB in ${result.duration.toSeconds()}s)"
    is BackupResult.Skipped -> "Scheduled backup skipped: ${result.reason}"
    is BackupResult.Failed -> {
        Telemetry.captureBackupFailure(result)
        "Scheduled backup failed: ${result.cause.message}"
    }
}
```

> Why this site, not inside the orchestrator: `BackupOrchestrator` is reusable and shouldn't depend on `Telemetry`. The entrypoint owns the user-facing wiring (notifier + telemetry); orchestrator just returns a result.

- [ ] **Step 7: Call `refreshConfigContext` from `DBHVaultRuntime.applyConfig`**

In `DBHVaultRuntime.kt`, add the import:

```kotlin
import dev.skrasek.dbhvault.observability.Telemetry
```

Replace the body of `applyConfig` with:

```kotlin
fun applyConfig(next: Config, summary: String, source: CommandSourceStack) {
    configHolder.set(next)
    configManager.save(next)
    scheduler.updateConfig(next.schedule)
    Telemetry.refreshConfigContext(next)
    notifier.send(next.notifications.configEvents, "$summary (by ${source.textName})")
}
```

> Order matters: `refreshConfigContext` happens *before* `notifier.send` so any error during the broadcast (rare, but possible) is captured under the *new* config — that's the config that produced the error.

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL — every existing test passes, plus the four `initInternal` tests in `TelemetryTest` (which the user fixed in Task 3).

If anything regresses, do not skip — diagnose. Likely culprits:
- Logger name change broke a test that asserted on logger output by name (unlikely; existing tests don't assert on logger names).
- Missing import for `CoroutineExceptionHandler` (compile error).

- [ ] **Step 9: Build the jar to confirm packaging is intact**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL. Inspect the jar:

```bash
unzip -p build/libs/dbhvault-1.0.0.jar dbhvault.build.properties
```

Expected: `mod.version=1.0.0` and `sentry.dsn=` (still blank — no `local.properties` configured).

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/dev/skrasek/dbhvault/DBHVault.kt src/main/kotlin/dev/skrasek/dbhvault/DBHVaultRuntime.kt
git commit -m "$(cat <<'EOF'
Task 4: wire Telemetry into runtime

Calls Telemetry.init/shutdown around server lifecycle, attaches a
CoroutineExceptionHandler to the IO scope, refreshes the Sentry config
context on bootstrap and on /vault config changes, and captures
BackupResult.Failed at the entrypoint's describe() site. Switches the
DBHVault logger to a class-based name so the Log4j2 appender's
hierarchy filter sees its events.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Smoke test the integration

**Goal:** Verify the wired integration end-to-end: dev runs with no DSN are silent; release builds with a DSN actually emit events.

**Files:** None modified (verification only).

### Steps

- [ ] **Step 1: Start dev server with no DSN configured**

Confirm `local.properties` does not exist (or its `sentry.dsn` is blank):

```bash
ls /Users/hskrasek/Documents/Projects/Minecraft/dbhv-backups/local.properties 2>/dev/null || echo "no local.properties — DSN will be blank"
```

Run the dev server in the background:

```bash
./gradlew runServer
```

Expected console output (early in startup):
- The mod's `logger.info("Opening the vault for {}", MOD_ID)` line appears.
- *Either* a WARN log saying telemetry is disabled (DSN absent) *or* no Sentry log at all — both are acceptable depending on how the user logged the no-op path in Task 3.
- **No `ERROR` or stack trace** mentioning `io.sentry`, `Sentry.init`, or `SentryAppender`.

Stop the server with `/stop` from the in-game console (or Ctrl-C the gradle process).

- [ ] **Step 2: Configure a real DSN and rebuild**

Copy the example file:

```bash
cp local.properties.example local.properties
```

Edit `local.properties` and set `sentry.dsn=` to your Sentry SaaS project's public DSN (from Settings → Client Keys → DSN).

Rebuild from clean to re-template the resource:

```bash
./gradlew clean build -x test
```

Confirm the DSN was baked in (extract from the built jar):

```bash
unzip -p build/libs/dbhvault-1.0.0.jar dbhvault.build.properties
```

Expected: `sentry.dsn=https://...@o....ingest.sentry.io/...`

- [ ] **Step 3: Launch dev server with DSN; confirm no startup error**

Run:

```bash
./gradlew runServer
```

Expected:
- The mod loads.
- No stack trace involving Sentry init.
- (Optional, depending on the user's logging in Task 3) An INFO log saying telemetry is initialized with release `dbhvault@1.0.0`.

- [ ] **Step 4: Trigger a controllable failure to verify event delivery**

In the running dev server's console, run:

```
/vault config set backup-directory /this/path/does/not/exist/and/cannot/be/created
/vault backup
```

Expected:
- The in-game notifier reports `Backup failed: ...`.
- Within ~30s, an event appears in the Sentry SaaS issues view, tagged with:
  - Release `dbhvault@1.0.0`
  - Tags `minecraft.version`, `fabric.loader.version`
  - Context block `backup` reflecting the (broken) config
  - **No `user` field** (PII scrubbing verified)
  - If implemented: fingerprint `backup-failure` (subsequent failures group together)

If the Sentry dashboard does not receive the event:
- Check that the DSN baked into the jar is the one shown in Sentry's project settings.
- Check the dev server's logs for an `io.sentry` WARN about transport failure (network issue).
- Check that `Sentry.captureException` was actually invoked — drop a temporary `println` or `logger.info` in `describe()`'s Failed arm to confirm the call is reached.

- [ ] **Step 5: Restore dev defaults**

Stop the server. Reset `local.properties` so subsequent `runServer` invocations are silent again:

```bash
echo "sentry.dsn=" > local.properties
./gradlew clean build -x test  # re-templates the resource with blank DSN
```

Expected: jar's `dbhvault.build.properties` has a blank `sentry.dsn=` again.

- [ ] **Step 6: Mark Task 5 complete and check the plan in**

```bash
# Edit this plan file: change all `- [ ]` for Tasks 1, 2, 4, 5 to `- [x]`.
# Task 3 is the user's, so they check it off when their impl lands.
git add docs/superpowers/plans/2026-05-10-sentry-integration.md
git commit -m "$(cat <<'EOF'
plan: check off Sentry integration tasks 1, 2, 4, 5

Smoke test passed: dev server with blank DSN is silent; with a real DSN
the BackupResult.Failed path emits a Sentry event tagged with the
expected release, MC/Loader versions, backup config context, and
backup-failure fingerprint, with no PII attached.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Spec coverage check

| Spec section | Where it's implemented in the plan |
| --- | --- |
| `Telemetry.kt` file with facade + sink interface + NoOpSink | Task 2, Step 1 (stub); Task 3 (user impl) |
| `dbhvault.build.properties` resource | Task 1, Step 7 |
| `local.properties.example` (committed) | Task 1, Step 1 |
| `local.properties` gitignored | Task 1, Step 2 |
| `gradle.properties` `sentry_version` pin | Task 1, Step 3 |
| `build.gradle.kts` Sentry dependency + DSN read + `expand` extension | Task 1, Steps 4–6 |
| `Telemetry.init()` first thing in `onInitializeServer` | Task 4, Step 2 |
| `Telemetry.shutdown()` in `SERVER_STOPPING` | Task 4, Step 3 |
| `CoroutineExceptionHandler` on IO scope | Task 4, Step 4 |
| `Telemetry.refreshConfigContext` on bootstrap | Task 4, Step 5 |
| `Telemetry.refreshConfigContext` on `applyConfig` | Task 4, Step 7 |
| `Telemetry.captureBackupFailure` in `describe()` Failed arm | Task 4, Step 6 |
| Logger name change (`MOD_ID` → class-based) | Task 4, Step 1 |
| Log4j2 appender attached to `dev.skrasek.dbhvault` logger config | Task 3 (user impl, sketch provided) |
| `BeforeSendCallback` strips `event.user` | Task 3 (user impl, contract item 2) |
| Release tag `dbhvault@<mod_version>` | Task 3 (user impl, contract item 4) |
| MC + Fabric Loader version tags | Task 3 (user impl, contract item 4) |
| Backup config context block | Task 3 (user impl, contract item 4) |
| `TelemetryTest` covering NoOp default + sink delegation + `initInternal` branches | Task 2, Step 2 |
| Smoke verification with blank vs real DSN | Task 5 |
| ConfigManager `captureException` (spec said yes; plan drops as redundant) | **Deviation documented at top of plan** |
| BackupOrchestrator integration assertion (spec said yes; plan drops) | **Deviation documented at top of plan** |
