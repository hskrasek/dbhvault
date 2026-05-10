package dev.skrasek.dbhvault.backup.archive

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.slf4j.LoggerFactory

/**
 * Selects the right [BackupArchiver] for a requested [ArchiveFormat], with a
 * graceful fallback to [ZipArchiver] when zstd-jni's native libs can't load
 * on the host (rare, but happens on hardened/sandboxed servers).
 *
 * [zstdAvailable] is constructor-injected so the fallback path is testable
 * without manipulating the native lib loader. The production call site uses
 * [detectZstdAvailable] as the default.
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
