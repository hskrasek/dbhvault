package dev.skrasek.dbhvault.observability

import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.config.Config
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.log4j2.SentryAppender
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LoggerContext
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Sink-based facade for telemetry/error reporting.
 *
 * Default sink is [NoOpSink] so the mod is fully functional with no Sentry
 * configured and so test code remains hermetic without explicit mocking.
 * Calling [init] swaps the sink to a Sentry-backed implementation iff a
 * non-blank DSN is present in the `dbhvault.build.properties` resource.
 */
object Telemetry {
    private val logger = LoggerFactory.getLogger(Telemetry::class.java)

    @Volatile
    private var sink: TelemetrySink = NoOpSink

    /**
     * Initialize from the bundled `dbhvault.build.properties` resource. If the
     * `sentry.dsn` value is blank, this is a no-op (the sink stays [NoOpSink]).
     * Any internal failure is caught and logged at WARN — telemetry init never
     * propagates an exception.
     */
    @JvmStatic
    fun init() {
        try {
            val props = Telemetry::class.java.classLoader
                .getResourceAsStream(BUILD_PROPERTIES_RESOURCE)
                ?.use { stream -> Properties().apply { load(stream) } }
            if (props == null) {
                logger.warn("dbhvault.build.properties resource not found; telemetry disabled")
                return
            }
            val dsn = props.getProperty("sentry.dsn", "")
            val modVersion = props.getProperty("mod.version", "unknown")
            initInternal(dsn, modVersion)
        } catch (t: Throwable) {
            logger.warn("Telemetry init failed; continuing with NoOpSink", t)
        }
    }

    /**
     * Test/internal seam: initialize with an explicit DSN. Used by tests to
     * exercise the blank-vs-non-blank branches without touching the
     * `dbhvault.build.properties` resource.
     */
    internal fun initInternal(dsn: String, modVersion: String = "unknown") {
        if (dsn.isBlank()) return
        try {
            Sentry.init { options ->
                options.dsn = dsn
                options.isSendDefaultPii = false
                options.release = "dbhvault@$modVersion"
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                    event.user = null
                    event
                }
            }
            attachLog4j2Appender()
            sink = SentrySink()
            logger.info("Telemetry initialized (release dbhvault@{})", modVersion)
        } catch (t: Throwable) {
            logger.warn("Sentry init failed; continuing with NoOpSink", t)
        }
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

    // ---- Internals ----

    private const val BUILD_PROPERTIES_RESOURCE = "dbhvault.build.properties"

    private fun attachLog4j2Appender() {
        val ctx = LoggerContext.getContext(false) as LoggerContext
        val cfg = ctx.configuration
        val appender = SentryAppender.createAppender(
            /* name = */ "Sentry",
            /* minimumBreadcrumbLevel = */ Level.INFO,
            /* minimumEventLevel = */ Level.ERROR,
            /* dsn = */ null,
            /* sendDefaultPii = */ false,
            /* options = */ null,
            /* filter = */ null,
        ) ?: error("SentryAppender.createAppender returned null")
        appender.start()
        cfg.addAppender(appender)
        val loggerCfg = cfg.getLoggerConfig("dev.skrasek.dbhvault")
        loggerCfg.addAppender(appender, Level.WARN, null)
        ctx.updateLoggers()
    }
}

/**
 * Sink contract. Implementations:
 *  - [NoOpSink]: default, used in dev builds and tests.
 *  - [SentrySink]: production, wraps `io.sentry.Sentry`.
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

internal class SentrySink : TelemetrySink {
    private val mcVersion: String = readMcVersion()
    private val loaderVersion: String = readFabricLoaderVersion()

    init {
        Sentry.configureScope { scope ->
            scope.setTag("minecraft.version", mcVersion)
            scope.setTag("fabric.loader.version", loaderVersion)
        }
    }

    override fun refreshConfigContext(config: Config) {
        Sentry.configureScope { scope ->
            scope.setContexts(
                "backup",
                mapOf(
                    "archive.format" to config.compression.format.name,
                    "retention.keepLast" to config.retention.keepLast,
                    "retention.keepWithinDays" to config.retention.keepWithinDays,
                    "schedule.enabled" to config.schedule.enabled,
                    "schedule.intervalHours" to config.schedule.intervalHours,
                    "schedule.idleSkip.enabled" to config.schedule.idleSkip.enabled,
                ),
            )
        }
    }

    override fun captureException(t: Throwable) {
        Sentry.captureException(t)
    }

    override fun captureBackupFailure(failure: BackupResult.Failed) {
        Sentry.withScope { scope ->
            scope.fingerprint = listOf("backup-failure")
            Sentry.captureException(failure.cause)
        }
    }

    override fun shutdown() {
        Sentry.close()
    }

    private fun readMcVersion(): String = try {
        SharedConstants.getCurrentVersion().name()
    } catch (t: Throwable) {
        "unknown"
    }

    private fun readFabricLoaderVersion(): String = try {
        FabricLoader.getInstance().getModContainer("fabricloader")
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
    } catch (t: Throwable) {
        "unknown"
    }
}
