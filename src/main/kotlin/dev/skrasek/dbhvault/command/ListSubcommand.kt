package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import dev.skrasek.dbhvault.permissions.Permissions
import dev.skrasek.dbhvault.util.humanBytes
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
                        val lines = buildString {
                            appendLine("DBHVault — ${entries.size} backup(s):")
                            for (e in entries) {
                                val pin = if (e.metadata.isPinned) " [pinned: ${e.metadata.name}]" else ""
                                appendLine("  ${FORMAT.format(e.metadata.timestamp)}  ${humanBytes(e.sizeBytes)}  ${e.path.fileName}$pin")
                            }
                        }.trimEnd()
                        ctx.source.sendSuccess({ Component.literal(lines) }, false)
                    }
                    1
                }
        )
    }
}
