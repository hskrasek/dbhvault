package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import dev.skrasek.dbhvault.util.Messages
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.time.Duration
import java.time.Instant

object InfoSubcommand {
    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {
        root.then(
            Commands.literal("info")
                .requires { Permissions.check(it, Permissions.Node.INFO, fallbackOp = 2) }
                .executes { ctx ->
                    val cfg = runtime.config()
                    val recent = runtime.registry.mostRecent()
                    val recentLine: Component = recent?.let { entry ->
                        val ago = Duration.between(entry.metadata.timestamp, Instant.now()).toHours()
                        Component.literal("Last backup: ${entry.path.fileName} (")
                            .append(Messages.size(entry.sizeBytes))
                            .append(Component.literal(", ${ago}h ago)"))
                    } ?: Component.literal("Last backup: (none)")
                    val sched = if (cfg.schedule.enabled) "every ${cfg.schedule.intervalHours}h" else "disabled"
                    val ret = "keepLast=${cfg.retention.keepLast}, keepWithinDays=${cfg.retention.keepWithinDays}"
                    val idle = if (cfg.schedule.idleSkip.enabled) "after ${cfg.schedule.idleSkip.afterIdleHours}h idle" else "disabled"
                    ctx.source.sendSuccess({
                        Component.empty()
                            .append(Messages.brand())
                            .append(Component.literal(" — schedule: $sched | retention: $ret | idle-skip: $idle\n"))
                            .append(recentLine)
                    }, false)
                    1
                }
        )
    }
}
