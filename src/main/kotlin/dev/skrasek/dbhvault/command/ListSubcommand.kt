package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import dev.skrasek.dbhvault.util.Messages
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object ListSubcommand {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneOffset.UTC)

    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {
        root.then(
            Commands.literal("list")
                .requires { Permissions.check(it, Permissions.Node.LIST, fallbackOp = 2) }
                .executes { ctx ->
                    val entries = runtime.registry.list()
                    if (entries.isEmpty()) {
                        ctx.source.sendSuccess({ Component.literal("No backups found.") }, false)
                    } else {
                        val body = Component.empty()
                            .append(Messages.brand())
                            .append(Component.literal(" — ${entries.size} backup(s):"))
                        for (e in entries) {
                            val pin = if (e.metadata.isPinned) " [pinned: ${e.metadata.name}]" else ""
                            body.append(Component.literal("\n  ${FORMAT.format(e.metadata.timestamp)}  "))
                                .append(Messages.size(e.sizeBytes))
                                .append(Component.literal("  ${e.path.fileName}$pin"))
                        }
                        ctx.source.sendSuccess({ body }, false)
                    }
                    1
                }
        )
    }
}
