package dev.skrasek.dbhvault.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

object VaultCommand {
    fun register(runtime: DBHVaultRuntime) {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            register(dispatcher, runtime)
        }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>, runtime: DBHVaultRuntime) {
        val root: LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("vault")
        BackupSubcommand.register(root, runtime)
        ListSubcommand.register(root, runtime)
        InfoSubcommand.register(root, runtime)
        ScheduleSubcommand.register(root, runtime)
        RetentionSubcommand.register(root, runtime)
        IdleSubcommand.register(root, runtime)
        ConfigSubcommand.register(root, runtime)
        dispatcher.register(root)
    }
}
