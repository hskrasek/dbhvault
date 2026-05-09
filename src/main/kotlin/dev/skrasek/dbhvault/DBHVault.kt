package dev.skrasek.dbhvault

import net.fabricmc.api.DedicatedServerModInitializer
import org.slf4j.LoggerFactory

object DBHVault : DedicatedServerModInitializer {
    const val MOD_ID = "dbhvault"

    private val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeServer() {
        logger.info("Opening the vault for {}", MOD_ID)
    }
}
