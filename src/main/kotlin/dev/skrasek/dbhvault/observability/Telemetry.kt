package dev.skrasek.dbhvault.observability

import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.Config

/**
 * Sink-based facade for telemetry/error reporting.
 *
 * Default sink is [NoOpSink] so the mod is fully functional with no Sentry
 * configured and so test code remains hermetic without explicit mocking. Calling
 * [init] swaps the sink to a Sentry-backed implementation iff a non-blank DSN
 * is present in the `dbhvault.build.properties` resource.
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
        TODO(
            "user implements: read dbhvault.build.properties from classpath, " +
                "delegate to initInternal(dsn)",
        )
    }

    /**
     * Test/internal seam: initialize with an explicit DSN. Used by tests to
     * exercise the blank-vs-non-blank branches without touching the
     * `dbhvault.build.properties` resource.
     */
    internal fun initInternal(dsn: String) {
        TODO(
            "user implements: if dsn.isBlank() keep NoOpSink, else " +
                "construct SentrySink, call Sentry.init { ... }, attach Log4j2 " +
                "appender to dev.skrasek.dbhvault logger config, swap sink",
        )
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
