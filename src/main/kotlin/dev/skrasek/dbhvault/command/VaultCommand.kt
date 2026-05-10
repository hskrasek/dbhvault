package dev.skrasek.dbhvault.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

object VaultCommand {
    /**
     * Register through Fabric's [CommandRegistrationCallback].
     *
     * NOTE: this only works if called before `MinecraftServer.initServer()`
     * runs. In a `DedicatedServerModInitializer`'s bootstrap (which we run
     * during `SERVER_STARTING`), Commands has already been constructed on
     * the main thread before our hook fires — use [registerDirect] in that
     * case instead.
     */
    fun register(runtime: DBHVaultRuntime) {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerDirect(dispatcher, runtime)
        }
    }

    /**
     * Register the `/vault` command tree directly onto a live dispatcher.
     * Use this from inside SERVER_STARTING / SERVER_STARTED, where the
     * dispatcher is already constructed and Fabric's callback would never
     * fire again until a `/reload`.
     */
    fun registerDirect(dispatcher: CommandDispatcher<CommandSourceStack>, runtime: DBHVaultRuntime) {
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
