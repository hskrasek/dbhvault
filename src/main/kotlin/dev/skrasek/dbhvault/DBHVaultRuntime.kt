package dev.skrasek.dbhvault

import dev.skrasek.dbhvault.backup.BackupOrchestrator
import dev.skrasek.dbhvault.backup.storage.BackupRegistry
import dev.skrasek.dbhvault.config.Config
import dev.skrasek.dbhvault.config.ConfigManager
import dev.skrasek.dbhvault.notify.Notifier
import dev.skrasek.dbhvault.schedule.BackupScheduler
import dev.skrasek.dbhvault.schedule.IdleTracker
import kotlinx.coroutines.CoroutineScope
import net.minecraft.commands.CommandSourceStack
import java.util.concurrent.atomic.AtomicReference

class DBHVaultRuntime(
    val scope: CoroutineScope,
    val configManager: ConfigManager,
    val orchestrator: BackupOrchestrator,
    val registry: BackupRegistry,
    val scheduler: BackupScheduler,
    val idleTracker: IdleTracker,
    val notifier: Notifier,
    initialConfig: Config,
) {
    private val configHolder = AtomicReference(initialConfig)

    fun config(): Config = configHolder.get()

    fun applyConfig(next: Config, summary: String, source: CommandSourceStack) {
        configHolder.set(next)
        configManager.save(next)
        scheduler.updateConfig(next.schedule)
        notifier.send(next.notifications.configEvents, "$summary (by ${source.textName})")
    }
}
