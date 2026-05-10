package dev.skrasek.dbhvault.observability

import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.Config
import io.sentry.SentryEvent
import io.sentry.protocol.User
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.DefaultConfiguration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
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

    // ---- filterSentryEvent ----

    @Test
    fun `filterSentryEvent drops events whose logger is outside the mod namespace`() {
        // Simulates the case where Log4j2's SentryAppender scraped an ERROR
        // from vanilla Minecraft networking code — exactly the DBHVAULT-2
        // shape — and we don't want it reported.
        val event = SentryEvent().apply {
            logger = "net.minecraft.network.PacketEncoder"
        }

        assertNull(Telemetry.filterSentryEvent(event))
    }

    @Test
    fun `filterSentryEvent drops events whose logger is a sibling namespace, not a prefix`() {
        // "dev.skrasek.other" must NOT match — startsWith is a prefix check on
        // the full name, not the dotted segments. A namespace check that
        // accidentally allowed "dev.skrasekother" or similar would be a bug,
        // but more importantly we want to confirm sibling packages are dropped.
        val event = SentryEvent().apply {
            logger = "dev.skrasek.other"
        }

        assertNull(Telemetry.filterSentryEvent(event))
    }

    @Test
    fun `filterSentryEvent keeps events from the mod's logger namespace`() {
        val event = SentryEvent().apply {
            logger = "dev.skrasek.dbhvault.backup.Scheduler"
        }

        val kept = Telemetry.filterSentryEvent(event)

        assertSame(event, kept)
    }

    @Test
    fun `filterSentryEvent keeps events whose logger exactly matches the mod namespace`() {
        val event = SentryEvent().apply {
            logger = "dev.skrasek.dbhvault"
        }

        val kept = Telemetry.filterSentryEvent(event)

        assertSame(event, kept)
    }

    @Test
    fun `filterSentryEvent keeps events with no logger set (explicit captureException path)`() {
        // Direct Sentry.captureException calls — used by SentrySink for our
        // own thrown-and-caught code — do not set event.logger. Those must
        // continue to flow even though they came from inside captureException.
        val event = SentryEvent()
        assertNull(event.logger, "precondition: a fresh SentryEvent has no logger")

        val kept = Telemetry.filterSentryEvent(event)

        assertSame(event, kept)
    }

    @Test
    fun `filterSentryEvent clears the user on kept events for PII protection`() {
        // The pre-existing behavior of beforeSend was to strip user info
        // before sending. Tightening with a logger filter must preserve it.
        val event = SentryEvent().apply {
            logger = "dev.skrasek.dbhvault"
            user = User().apply { username = "leak-me" }
        }

        val kept = Telemetry.filterSentryEvent(event)

        assertSame(event, kept)
        assertNull(kept!!.user, "user should be stripped before send")
    }

    // ---- resolveOrCreateModLoggerConfig ----

    @Test
    fun `resolveOrCreateModLoggerConfig creates a dedicated config when none exists at the exact name`() {
        // A bare DefaultConfiguration has only the root LoggerConfig (name "").
        // getLoggerConfig("dev.skrasek.dbhvault") on it returns the root by
        // ancestor lookup — which is exactly the failure mode DBHVAULT-2
        // exposed. The helper must detect that and create a dedicated config.
        val cfg = DefaultConfiguration()
        val ancestor = cfg.getLoggerConfig(Telemetry.MOD_LOGGER_NAME)
        assertEquals(
            "",
            ancestor.name,
            "precondition: a fresh DefaultConfiguration falls back to root for mod namespace",
        )

        val resolved = Telemetry.resolveOrCreateModLoggerConfig(cfg)

        assertEquals(Telemetry.MOD_LOGGER_NAME, resolved.name)
        assertNotSame(ancestor, resolved, "must not reuse the ancestor (root) config")
    }

    @Test
    fun `resolveOrCreateModLoggerConfig does not attach appenders to the root logger`() {
        // The whole point: lookups for foreign loggers must still resolve to
        // root after we've registered the mod config. If we had mutated root
        // instead, this would silently match the mod config.
        val cfg = DefaultConfiguration()

        val resolved = Telemetry.resolveOrCreateModLoggerConfig(cfg)

        val rootForForeign = cfg.getLoggerConfig("net.minecraft.network.PacketEncoder")
        assertNotSame(resolved, rootForForeign)
        assertEquals(
            "",
            rootForForeign.name,
            "foreign loggers must still resolve to the (untouched) root config",
        )
    }

    @Test
    fun `resolveOrCreateModLoggerConfig reuses an existing config with the exact name`() {
        // Be idempotent if someone (test harness, custom log4j config) has
        // already registered a logger at the mod's name — don't replace it.
        val cfg = DefaultConfiguration()
        val preexisting = LoggerConfig(Telemetry.MOD_LOGGER_NAME, Level.WARN, true)
        cfg.addLogger(Telemetry.MOD_LOGGER_NAME, preexisting)

        val resolved = Telemetry.resolveOrCreateModLoggerConfig(cfg)

        assertSame(preexisting, resolved)
    }

    @Test
    fun `resolveOrCreateModLoggerConfig new config is additive so mod logs still reach the server console`() {
        // Server-only mod: stdout is the only UI. If we accidentally created
        // the LoggerConfig with additive=false, our INFO/WARN/ERROR lines
        // would stop appearing in the Minecraft server log.
        val cfg = DefaultConfiguration()

        val resolved = Telemetry.resolveOrCreateModLoggerConfig(cfg)

        assertTrue(resolved.isAdditive, "mod logger config must be additive")
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
