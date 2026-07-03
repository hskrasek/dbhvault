package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

internal object ConfigSubcommand {
    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {
        root.then(
            Commands.literal("config")
                .requires { Permissions.check(it, Permissions.Node.CONFIG, fallbackOp = 4) }
                .then(
                    Commands.literal("reload")
                        .executes { ctx ->
                            // tryLoad, not loadOrCreate: a malformed file must fail
                            // the reload, not get silently replaced with defaults
                            // (applyConfig saves, which would clobber the file).
                            val next = runtime.configManager.tryLoad()
                            if (next == null) {
                                ctx.source.sendFailure(
                                    Component.literal("Config file failed to parse — see server log. Running config unchanged.")
                                )
                                0
                            } else {
                                runtime.applyConfig(next, "Config reloaded from disk", ctx.source)
                                1
                            }
                        }
                )
                .then(
                    Commands.literal("show")
                        .executes { ctx ->
                            val cfg = runtime.config()
                            ctx.source.sendSuccess({
                                Component.literal(
                                    "DBHVault config:\n" +
                                        "  backupDirectory=${cfg.backupDirectory}\n" +
                                        "  schedule.enabled=${cfg.schedule.enabled}\n" +
                                        "  schedule.intervalHours=${cfg.schedule.intervalHours}\n" +
                                        "  idleSkip.enabled=${cfg.schedule.idleSkip.enabled}\n" +
                                        "  idleSkip.afterIdleHours=${cfg.schedule.idleSkip.afterIdleHours}\n" +
                                        "  retention.keepLast=${cfg.retention.keepLast}\n" +
                                        "  retention.keepWithinDays=${cfg.retention.keepWithinDays}\n" +
                                        "  compression.format=${cfg.compression.format}\n" +
                                        "  compression.level=${cfg.compression.level}\n" +
                                        "  notifications.backupEvents=${cfg.notifications.backupEvents}\n" +
                                        "  notifications.configEvents=${cfg.notifications.configEvents}"
                                )
                            }, false)
                            1
                        }
                )
        )
    }
}
