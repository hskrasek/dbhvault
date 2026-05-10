package dev.skrasek.dbhvault.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands

internal object RetentionSubcommand {
    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {
        root.then(
            Commands.literal("retention")
                .requires { Permissions.check(it, Permissions.Node.RETENTION, fallbackOp = 4) }
                .then(
                    Commands.literal("keep-last")
                        .then(
                            Commands.argument("count", IntegerArgumentType.integer(1, 10000))
                                .executes { ctx ->
                                    val n = IntegerArgumentType.getInteger(ctx, "count")
                                    val current = runtime.config()
                                    runtime.applyConfig(
                                        current.copy(retention = current.retention.copy(keepLast = n)),
                                        "Retention keepLast set to $n",
                                        ctx.source,
                                    )
                                    1
                                }
                        )
                )
                .then(
                    Commands.literal("keep-within-days")
                        .then(
                            Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes { ctx ->
                                    val d = IntegerArgumentType.getInteger(ctx, "days")
                                    val current = runtime.config()
                                    runtime.applyConfig(
                                        current.copy(retention = current.retention.copy(keepWithinDays = d)),
                                        "Retention keepWithinDays set to $d",
                                        ctx.source,
                                    )
                                    1
                                }
                        )
                )
        )
    }
}
