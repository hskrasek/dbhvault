package dev.skrasek.dbhvault

import dev.skrasek.dbhvault.backup.BackupOrchestrator
import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.backup.WorldFlush
import dev.skrasek.dbhvault.backup.archive.ArchiverFactory
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.backup.storage.HybridRetention
import dev.skrasek.dbhvault.command.VaultCommand
import dev.skrasek.dbhvault.config.ConfigManager
import dev.skrasek.dbhvault.notify.Notifier
import dev.skrasek.dbhvault.observability.Telemetry
import dev.skrasek.dbhvault.schedule.BackupScheduler
import dev.skrasek.dbhvault.schedule.IdleTracker
import dev.skrasek.dbhvault.util.Messages
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DBHVault : DedicatedServerModInitializer {
    const val MOD_ID = "dbhvault"

    private val logger = LoggerFactory.getLogger(DBHVault::class.java)

    /**
     * Holds the live runtime so callbacks captured before bootstrap (the
     * scheduler's `runBackup`/`shouldSkipIdle`, the player-connection
     * listeners) can reach the current config without the runtime being
     * created yet.
     */
    private val runtimeRef = AtomicReference<DBHVaultRuntime?>(null)

    override fun onInitializeServer() {
        Telemetry.init()
        logger.info("Opening the vault for {}", MOD_ID)

        // Bootstrap during SERVER_STARTING — this fires *before* MinecraftServer.initServer(),
        // which is where Commands is constructed and CommandRegistrationCallback fires.
        // Doing the bootstrap (and VaultCommand.register) here ensures `/vault` is wired
        // into the initial command tree, so it works without a `/reload`.
        ServerLifecycleEvents.SERVER_STARTING.register { server -> bootstrap(server) }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            runtimeRef.getAndSet(null)?.let { runtime ->
                runtime.scheduler.stop()
                runtime.scope.cancel()
            }
            Telemetry.shutdown()
        }

        // Both events prove a player was online right now — record activity
        // unconditionally. The exact remaining player count doesn't matter;
        // "is anyone online" is read live from the player list at skip time.
        ServerPlayConnectionEvents.JOIN.register { _, _, _ ->
            runtimeRef.get()?.idleTracker?.recordActivity(Instant.now())
        }
        ServerPlayConnectionEvents.DISCONNECT.register { _, _ ->
            runtimeRef.get()?.idleTracker?.recordActivity(Instant.now())
        }
    }

    private fun bootstrap(server: MinecraftServer) {
        val configManager = ConfigManager(Paths.get("config", "dbhvault.toml"))
        val cfg = configManager.loadOrCreate()
        Telemetry.refreshConfigContext(cfg)

        val worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val requestedBackupDir = Paths.get(cfg.backupDirectory).toAbsolutePath().normalize()
        // A backup dir inside the world dir would make every archive try to
        // include its own growing output file — guaranteed corruption.
        val backupDir = if (requestedBackupDir.startsWith(worldDir)) {
            logger.error(
                "backupDirectory {} is inside the world directory {} — backups would recursively " +
                    "archive themselves. Falling back to ./backups; fix backupDirectory in dbhvault.toml.",
                requestedBackupDir,
                worldDir,
            )
            Paths.get("backups").toAbsolutePath().normalize()
        } else {
            requestedBackupDir
        }
        Files.createDirectories(backupDir)

        val archiverFactory = ArchiverFactory()
        val effectiveFormat = archiverFactory.effectiveFormat(cfg.compression.format)
        val archiver = archiverFactory.create(cfg.compression.format)
        if (effectiveFormat != cfg.compression.format) {
            logger.warn(
                "DBHVault: requested archive format {} unavailable, falling back to {}",
                cfg.compression.format,
                effectiveFormat,
            )
        }

        val registry = BackupRegistry(backupDir)
        // Provider, not snapshot: reads the live runtime config so `/vault retention`
        // edits prune correctly on the next backup without a restart. (`cfg.retention`
        // is only the fallback for the startup window before runtimeRef is set.)
        // ponytail: cfg.compression below is still a startup snapshot — a runtime
        // format/level change won't take effect until restart. Not reported, left as-is.
        val retention = HybridRetention { runtimeRef.get()?.config()?.retention ?: cfg.retention }
        val idleTracker = IdleTracker(initialActivity = Instant.now())
        val notifier = Notifier(server)
        val scope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, t -> Telemetry.captureException(t) },
        )

        // The orchestrator must see the *effective* format, not the requested
        // one — otherwise a zstd-unavailable host writes zip bytes into a file
        // named .tar.zst (and metadata parses the wrong format at restore time).
        val effectiveCfg =
            if (effectiveFormat != cfg.compression.format) {
                cfg.copy(compression = cfg.compression.copy(format = effectiveFormat))
            } else {
                cfg
            }

        val orchestrator = BackupOrchestrator(
            config = effectiveCfg,
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
                entries.forEach { entry ->
                    runCatching { Files.deleteIfExists(entry.path) }
                        .onFailure { logger.warn("Failed to prune backup {}", entry.path, it) }
                }
            },
        )

        val scheduler = BackupScheduler(
            scheduleConfig = cfg.schedule,
            shouldSkipIdle = {
                val current = runtimeRef.get()?.config()?.schedule?.idleSkip ?: cfg.schedule.idleSkip
                // The tracker only sees join/disconnect edges — a player who has
                // been online for days produces no events. Anyone online now
                // means the world is live: never skip.
                server.playerList.players.isEmpty() &&
                    idleTracker.shouldSkipScheduled(
                        current,
                        registry.mostRecent()?.metadata?.timestamp,
                        Instant.now(),
                    )
            },
            runBackup = { req ->
                val result = orchestrator.runIfFree(req)
                val broadcastScope = runtimeRef.get()?.config()?.notifications?.backupEvents
                    ?: cfg.notifications.backupEvents
                notifier.send(broadcastScope, describe(result))
                result
            },
        )

        val runtime = DBHVaultRuntime(
            scope = scope,
            configManager = configManager,
            orchestrator = orchestrator,
            registry = registry,
            scheduler = scheduler,
            idleTracker = idleTracker,
            notifier = notifier,
            initialConfig = cfg,
        )
        runtimeRef.set(runtime)

        // Register directly on the live dispatcher: Fabric's
        // CommandRegistrationCallback already fired (during the dedicated-server
        // worldStem load on the main thread, before SERVER_STARTING fires on
        // the server thread), so attaching a callback now would only take effect
        // after a /reload.
        VaultCommand.registerDirect(server.commands.dispatcher, runtime)
        scheduler.start(scope)

        logger.info(
            "DBHVault initialized: world={}, backupDir={}, schedule={}h enabled={}, " +
                "retention(keepLast={}, keepWithinDays={}), archive={}",
            worldDir,
            backupDir,
            cfg.schedule.intervalHours,
            cfg.schedule.enabled,
            cfg.retention.keepLast,
            cfg.retention.keepWithinDays,
            effectiveFormat,
        )
    }

    private fun describe(result: BackupResult): Component = when (result) {
        is BackupResult.Success ->
            Component.literal("Backup complete: ${result.file.fileName} (")
                .append(Messages.size(result.sizeBytes))
                .append(Component.literal(" in ${result.duration.toSeconds()}s)"))
        is BackupResult.Skipped -> Component.literal("Scheduled backup skipped: ${result.reason}")
        // Failure telemetry is captured by the orchestrator itself, so manual
        // and scheduled failures both reach Sentry.
        is BackupResult.Failed -> Component.literal("Scheduled backup failed: ${result.cause.message}")
    }

    /**
     * Hops to the server thread to run [block]. If already on the server
     * thread, runs synchronously to avoid a deadlock waiting on the future.
     *
     * `MinecraftServer.submit` is overloaded with both `Supplier<V>` and
     * `Runnable` variants — Kotlin's overload resolution picks the wrong one
     * for SAM-converted lambdas, so we hand-roll the future-completion dance
     * via `execute(Runnable)` instead.
     */
    private fun <T> runOnServerThread(server: MinecraftServer, block: () -> T): T {
        if (server.isSameThread) return block()
        val future = CompletableFuture<T>()
        server.execute {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        // Bounded wait: if the server thread stops draining tasks (shutdown
        // race), fail the backup instead of parking an IO thread forever.
        // 10 minutes comfortably covers a saveAll flush on a huge world.
        return future.get(10, TimeUnit.MINUTES)
    }
}
