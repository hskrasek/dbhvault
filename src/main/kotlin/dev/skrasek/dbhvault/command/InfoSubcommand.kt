package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
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
                    val recentLine = recent?.let { entry ->
                        val ago = Duration.between(entry.metadata.timestamp, Instant.now()).toHours()
                        "Last backup: ${entry.path.fileName} (${entry.sizeBytes / 1024 / 1024} MiB, ${ago}h ago)"
                    } ?: "Last backup: (none)"
                    val sched = if (cfg.schedule.enabled) "every ${cfg.schedule.intervalHours}h" else "disabled"
                    val ret = "keepLast=${cfg.retention.keepLast}, keepWithinDays=${cfg.retention.keepWithinDays}"
                    val idle = if (cfg.schedule.idleSkip.enabled) "after ${cfg.schedule.idleSkip.afterIdleHours}h idle" else "disabled"
                    ctx.source.sendSuccess({
                        Component.literal("DBHVault — schedule: $sched | retention: $ret | idle-skip: $idle\n$recentLine")
                    }, false)
                    1
                }
        )
    }
}
