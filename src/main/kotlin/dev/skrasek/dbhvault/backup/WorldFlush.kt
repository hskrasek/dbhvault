package dev.skrasek.dbhvault.backup

import net.minecraft.server.MinecraftServer

import org.slf4j.LoggerFactory

/**
 * Coordinates "freezing" the world for backup. Must be invoked on the server thread.
 *
 * Usage:
 *   val token = WorldFlush.freeze(server)
 *   try { archive(...) } finally { token.thaw() }
 *
 * `freeze` calls saveAll(suppressLog=true, flush=true, force=false) which forces region
 * files to flush to disk, then sets savingDisabled=true on every loaded ServerWorld so
 * mid-backup chunk saves don't race with the archive read.
 */
object WorldFlush  {
    private val logger = LoggerFactory.getLogger(WorldFlush::class.java)

    class FrozenToken(private val server: MinecraftServer) {
        fun thaw() {
            server.execute {
                for (level in server.allLevels) {
                    level.noSave = false
                }
                logger.debug("Saving re-enabled on all worlds")
            }
        }
    }

    fun freeze(server: MinecraftServer): FrozenToken {
        check(server.isSameThread) { "WorldFlush.freeze must be called on the server thread" }

        logger.debug("Flushing all worlds before backup")

        server.saveAllChunks(true, true, false)

        for (level in server.allLevels) {
            level.noSave = true
        }

        return FrozenToken(server)
    }
}