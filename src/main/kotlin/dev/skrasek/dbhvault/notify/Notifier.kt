package dev.skrasek.dbhvault.notify

import dev.skrasek.dbhvault.config.BroadcastScope
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Routes DBHVault status messages to the right audience based on a
 * [BroadcastScope]. Always logs; conditionally broadcasts to in-game players.
 *
 * - [BroadcastScope.LOG_ONLY]: log only, no in-game chat.
 * - [BroadcastScope.OPS_ONLY]: log + send a system message to every online op.
 * - [BroadcastScope.ALL_PLAYERS]: log + send a system message to every online player.
 *
 * Used in two distinct lanes per the configured `notifications` settings:
 *   - `backupEvents`: backup started / finished / failed
 *   - `configEvents`: schedule paused, retention changed, etc.
 *
 * The chat hop is dispatched onto the server thread via [MinecraftServer.execute]
 * because callers may invoke [send] from coroutine workers ([Dispatchers.IO]),
 * and Minecraft's networking expects mutations from the server thread.
 */
class Notifier(private val server: MinecraftServer) {
    private val logger = LoggerFactory.getLogger(Notifier::class.java)

    fun send(scope: BroadcastScope, message: String) {
        // Always log — operators grep the server log when chat history is unreliable.
        logger.info("[DBHVault] {}", message)
        if (scope == BroadcastScope.LOG_ONLY) return

        val text = Component.literal("[DBHVault] $message")
        server.execute {
            val playerList = server.playerList
            for (player in playerList.players) {
                if (scope == BroadcastScope.OPS_ONLY && !playerList.isOp(player.nameAndId())) continue
                player.sendSystemMessage(text)
            }
        }
    }
}
