package dev.skrasek.dbhvault.permissions

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.permissions.Permissions as MojangPermissions

/**
 * Permission gating for `/vault` subcommands.
 *
 * 26.1 introduced a typed permissions abstraction: `source.permissions()` returns
 * a `PermissionSet` and `hasPermission(Permission)` does the check. Op-level
 * integers map onto named `Permission` constants in
 * [net.minecraft.server.permissions.Permissions]:
 *
 *   - 1 → [MojangPermissions.COMMANDS_MODERATOR]
 *   - 2 → [MojangPermissions.COMMANDS_GAMEMASTER]
 *   - 3 → [MojangPermissions.COMMANDS_ADMIN]
 *   - 4 → [MojangPermissions.COMMANDS_OWNER]
 *
 * **LuckPerms note:** the original spec called for fabric-permissions-api
 * with LuckPerms fallback. As of 2026-05 that library has no 26.1-compatible
 * release (latest 0.4.x stops at 1.21.6 — see
 * https://github.com/lucko/fabric-permissions-api/releases). The [Node]
 * constants and the `node` parameter on [check] are retained as
 * forward-compatibility hooks: when a 26.1-ready permissions library lands,
 * the swap is a single-file change here.
 *
 * Op-level convention used by callers:
 *   - 4 (full op) for mutating commands: backup, schedule, retention, idle, config
 *   - 2 (gamemaster) for read-only commands: list, info
 */
object Permissions {
    private const val ROOT = "dbhvault"

    /** Permission nodes for each `/vault` subcommand (reserved for future LuckPerms wiring). */
    object Node {
        const val BACKUP = "$ROOT.command.backup"
        const val LIST = "$ROOT.command.list"
        const val INFO = "$ROOT.command.info"
        const val SCHEDULE = "$ROOT.command.schedule"
        const val RETENTION = "$ROOT.command.retention"
        const val IDLE = "$ROOT.command.idle"
        const val CONFIG = "$ROOT.command.config"
    }

    @Suppress("UNUSED_PARAMETER")
    fun check(source: CommandSourceStack, node: String, fallbackOp: Int = 4): Boolean {
        if (fallbackOp <= 0) return true
        val permission = when {
            fallbackOp >= 4 -> MojangPermissions.COMMANDS_OWNER
            fallbackOp >= 3 -> MojangPermissions.COMMANDS_ADMIN
            fallbackOp >= 2 -> MojangPermissions.COMMANDS_GAMEMASTER
            else -> MojangPermissions.COMMANDS_MODERATOR
        }
        return source.permissions().hasPermission(permission)
    }
}
