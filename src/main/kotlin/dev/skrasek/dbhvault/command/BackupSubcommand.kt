package dev.skrasek.dbhvault.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.skrasek.dbhvault.DBHVaultRuntime
import net.minecraft.commands.CommandSourceStack

internal object BackupSubcommand {
    fun register(root: LiteralArgumentBuilder<CommandSourceStack>, runtime: DBHVaultRuntime) {}
}
