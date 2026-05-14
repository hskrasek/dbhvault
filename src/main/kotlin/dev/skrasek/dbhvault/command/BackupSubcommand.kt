package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.backup.BackupRequest
import dev.skrasek.dbhvault.backup.BackupResult
import dev.skrasek.dbhvault.permissions.Permissions
import dev.skrasek.dbhvault.util.humanBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

internal object BackupSubcommand {
    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {
        root.then(
            Commands.literal("backup")
                .requires { Permissions.check(it, Permissions.Node.BACKUP, fallbackOp = 4) }
                .executes { trigger(it.source, runtime, name = null) }
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { trigger(it.source, runtime, StringArgumentType.getString(it, "name")) }
                )
        )
    }

    private fun trigger(source: CommandSourceStack, runtime: DBHVaultRuntime, name: String?): Int {
        source.sendSuccess({ Component.literal("Starting backup${if (name != null) " '$name'" else ""}...") }, false)
        runtime.scope.launch(Dispatchers.IO) {
            val result = runtime.orchestrator.runIfFree(BackupRequest.Manual(name))
            val msg = when (result) {
                is BackupResult.Success ->
                    "Backup complete: ${result.file.fileName} " +
                        "(${humanBytes(result.sizeBytes)} in ${result.duration.toSeconds()}s)"
                is BackupResult.Skipped -> "Backup skipped: ${result.reason}"
                is BackupResult.Failed -> "Backup failed: ${result.cause.message}"
            }
            runtime.notifier.send(runtime.config().notifications.backupEvents, msg)
        }
        return 1
    }
}
