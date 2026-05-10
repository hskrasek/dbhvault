package dev.skrasek.dbhvault.backup.archive

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiverFactoryTest {

    // ---- create() dispatch ----

    @Test
    fun `creates ZipArchiver for ZIP format`() {
        val factory = ArchiverFactory(zstdAvailable = true)
        assertTrue(
            factory.create(ArchiveFormat.ZIP) is ZipArchiver,
            "ZIP request should always produce a ZipArchiver",
        )
    }

    @Test
    fun `creates TarZstdArchiver for TAR_ZST when zstd is available`() {
        val factory = ArchiverFactory(zstdAvailable = true)
        assertTrue(
            factory.create(ArchiveFormat.TAR_ZST) is TarZstdArchiver,
            "TAR_ZST request should produce a TarZstdArchiver when zstd works",
        )
    }

    @Test
    fun `falls back to ZipArchiver when zstd is unavailable`() {
        val factory = ArchiverFactory(zstdAvailable = false)
        assertTrue(
            factory.create(ArchiveFormat.TAR_ZST) is ZipArchiver,
            "TAR_ZST request should fall back to ZipArchiver when zstd-jni native lib can't load",
        )
    }

    // ---- effectiveFormat() reporting ----

    @Test
    fun `effectiveFormat returns requested format when zstd available`() {
        val factory = ArchiverFactory(zstdAvailable = true)
        assertEquals(ArchiveFormat.TAR_ZST, factory.effectiveFormat(ArchiveFormat.TAR_ZST))
        assertEquals(ArchiveFormat.ZIP, factory.effectiveFormat(ArchiveFormat.ZIP))
    }

    @Test
    fun `effectiveFormat downgrades TAR_ZST to ZIP when zstd unavailable`() {
        val factory = ArchiverFactory(zstdAvailable = false)
        assertEquals(ArchiveFormat.ZIP, factory.effectiveFormat(ArchiveFormat.TAR_ZST))
    }

    @Test
    fun `effectiveFormat returns ZIP for ZIP request even when zstd unavailable`() {
        // ZIP is always ZIP; the zstd flag should only affect TAR_ZST.
        val factory = ArchiverFactory(zstdAvailable = false)
        assertEquals(ArchiveFormat.ZIP, factory.effectiveFormat(ArchiveFormat.ZIP))
    }
}
