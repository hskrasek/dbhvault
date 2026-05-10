package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

internal object ScheduleSubcommand {
    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {
        root.then(
            Commands.literal("schedule")
                .requires { Permissions.check(it, Permissions.Node.SCHEDULE, fallbackOp = 4) }
                .then(Commands.literal("pause").executes { setEnabled(it.source, runtime, enabled = false) })
                .then(Commands.literal("resume").executes { setEnabled(it.source, runtime, enabled = true) })
                .then(
                    Commands.literal("interval")
                        .then(
                            Commands.argument("hours", IntegerArgumentType.integer(1, 168))
                                .executes { setInterval(it.source, runtime, IntegerArgumentType.getInteger(it, "hours")) }
                        )
                )
        )
    }

    private fun setEnabled(source: CommandSourceStack, runtime: DBHVaultRuntime, enabled: Boolean): Int {
        val current = runtime.config()
        val next = current.copy(schedule = current.schedule.copy(enabled = enabled))
        runtime.applyConfig(next, "Schedule ${if (enabled) "resumed" else "paused"}", source)
        return 1
    }

    private fun setInterval(source: CommandSourceStack, runtime: DBHVaultRuntime, hours: Int): Int {
        val current = runtime.config()
        val next = current.copy(schedule = current.schedule.copy(intervalHours = hours))
        runtime.applyConfig(next, "Schedule interval set to ${hours}h", source)
        return 1
    }
}
