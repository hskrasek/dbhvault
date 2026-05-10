package dev.skrasek.dbhvault.backup.archive

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.slf4j.LoggerFactory

/**
 * Selects the right [BackupArchiver] for a requested [ArchiveFormat], with a
 * graceful fallback to [ZipArchiver] when zstd-jni's native libs can't be
 * loaded on the host (rare, but happens on hardened/sandboxed servers).
 *
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/backup/archive/ArchiverFactoryTest.kt`
 *
 * Behaviors specified by the tests:
 *  - [create]`(ZIP)` → [ZipArchiver]
 *  - [create]`(TAR_ZST)` → [TarZstdArchiver] when [zstdAvailable] is true,
 *    [ZipArchiver] when false
 *  - [effectiveFormat]`(req)` returns the format that [create] would actually
 *    produce, so callers can log or pick a file extension correctly.
 *
 * The constructor takes [zstdAvailable] as a parameter (rather than
 * detecting at runtime) to keep both code paths testable. The production
 * call site should pass [detectZstdAvailable] as the argument.
 */
class ArchiverFactory(
    private val zstdAvailable: Boolean = detectZstdAvailable(),
) {
    fun create(requested: ArchiveFormat): BackupArchiver = when(effectiveFormat(requested)) {
        ArchiveFormat.TAR_ZST -> TarZstdArchiver()
        ArchiveFormat.ZIP -> ZipArchiver()
    }

    fun effectiveFormat(requested: ArchiveFormat): ArchiveFormat =
        if (requested == ArchiveFormat.TAR_ZST && !zstdAvailable) ArchiveFormat.ZIP else requested

    companion object {
        private val logger = LoggerFactory.getLogger(ArchiverFactory::class.java)

        /**
         * Probes whether zstd-jni's native library can be loaded by
         * constructing and immediately closing a tiny [ZstdOutputStream].
         * Returns false on any throwable (UnsatisfiedLinkError, etc.).
         */
        fun detectZstdAvailable(): Boolean = try {
            com.github.luben.zstd.ZstdOutputStream(java.io.ByteArrayOutputStream()).close()
            true
        } catch (t: Throwable) {
            logger.warn("zstd-jni native init failed; .tar.zstd will fall back to .zip", t)
            false
        }
    }
}
