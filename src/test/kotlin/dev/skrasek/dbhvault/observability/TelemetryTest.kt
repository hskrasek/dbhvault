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
