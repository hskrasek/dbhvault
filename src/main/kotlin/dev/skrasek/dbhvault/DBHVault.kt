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

        ServerPlayConnectionEvents.JOIN.register { _, _, server ->
            runtimeRef.get()?.idleTracker?.playerCountChanged(
                server.playerList.players.size,
                Instant.now(),
            )
        }
        ServerPlayConnectionEvents.DISCONNECT.register { _, server ->
            // The disconnecting player is still in the list while this fires —
            // subtract one to reflect the post-disconnect player count.
            val remaining = (server.playerList.players.size - 1).coerceAtLeast(0)
            runtimeRef.get()?.idleTracker?.playerCountChanged(remaining, Instant.now())
        }
    }

    private fun bootstrap(server: MinecraftServer) {
        val configManager = ConfigManager(Paths.get("config", "dbhvault.toml"))
        val cfg = configManager.loadOrCreate()
        Telemetry.refreshConfigContext(cfg)

        val worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val backupDir = Paths.get(cfg.backupDirectory).toAbsolutePath().normalize()
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
        val retention = HybridRetention(cfg.retention)
        val idleTracker = IdleTracker(initialActivity = Instant.now())
        val notifier = Notifier(server)
        val scope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, t -> Telemetry.captureException(t) },
        )

        val orchestrator = BackupOrchestrator(
            config = cfg,
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
            prune = { entries -> entries.forEach { runCatching { it.path.toFile().delete() } } },
        )

        val scheduler = BackupScheduler(
            scheduleConfig = cfg.schedule,
            shouldSkipIdle = {
                val current = runtimeRef.get()?.config()?.schedule?.idleSkip ?: cfg.schedule.idleSkip
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
        is BackupResult.Failed -> {
            Telemetry.captureBackupFailure(result)
            Component.literal("Scheduled backup failed: ${result.cause.message}")
        }
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
        return future.get()
    }
}
