package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

internal object IdleSubcommand {
    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {
        root.then(
            Commands.literal("idle")
                .requires { Permissions.check(it, Permissions.Node.IDLE, fallbackOp = 4) }
                .then(Commands.literal("enable").executes { setEnabled(it.source, runtime, true) })
                .then(Commands.literal("disable").executes { setEnabled(it.source, runtime, false) })
                .then(
                    Commands.literal("after-hours")
                        .then(
                            Commands.argument("hours", IntegerArgumentType.integer(1, 720))
                                .executes { ctx ->
                                    val h = IntegerArgumentType.getInteger(ctx, "hours")
                                    val cur = runtime.config()
                                    runtime.applyConfig(
                                        cur.copy(schedule = cur.schedule.copy(idleSkip = cur.schedule.idleSkip.copy(afterIdleHours = h))),
                                        "Idle-skip threshold set to ${h}h",
                                        ctx.source,
                                    )
                                    1
                                }
                        )
                )
        )
    }

    private fun setEnabled(source: CommandSourceStack, runtime: DBHVaultRuntime, enabled: Boolean): Int {
        val cur = runtime.config()
        runtime.applyConfig(
            cur.copy(schedule = cur.schedule.copy(idleSkip = cur.schedule.idleSkip.copy(enabled = enabled))),
            "Idle-skip ${if (enabled) "enabled" else "disabled"}",
            source,
        )
        return 1
    }
}
