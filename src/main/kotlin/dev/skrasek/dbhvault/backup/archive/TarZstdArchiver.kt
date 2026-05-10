package dev.skrasek.dbhvault.backup.archive

import java.nio.file.Path

/**
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/backup/archive/TarZstdArchiverTest.kt`
 *
 * Format: a `tar` archive (USTAR) wrapped in a single zstd stream.
 *
 * Implementation notes from the test suite:
 *  - Walk [sourceDir] recursively; archive every regular file.
 *  - tar entry names: relative to [sourceDir], forward slashes only.
 *  - tar entry names are limited to 100 chars by USTAR — throw if any
 *    file's relative path exceeds that. (PAX/GNU long-name extensions
 *    are out of scope for v1; if you hit this in practice, switch the
 *    impl to a PAX-capable writer like commons-compress.)
 *  - End-of-archive: two empty 512-byte blocks (1024 zero bytes) per
 *    USTAR spec — the test verifies this implicitly via commons-compress
 *    correctly reading back the archive.
 *  - Wrap the whole tar stream in a single `ZstdOutputStream`; honor
 *    [level] via `ZstdOutputStream.setLevel(level)` (zstd supports 1..22).
 *  - Zero-byte files are valid tar entries with size=0 and no content.
 *  - Empty source dir produces a tar consisting only of the two trailing
 *    zero-blocks (also valid).
 *  - Throw if [sourceDir] is missing or not a directory.
 *  - Return `Files.size(destFile)`.
 *
 * Suggested deps for impl:
 *   `com.github.luben.zstd.ZstdOutputStream` (already on the classpath)
 *
 * Tar header format (USTAR, 512 bytes per header):
 *   0..99   name
 *   100..107 mode (octal "0000644 ")
 *   108..115 uid
 *   116..123 gid
 *   124..135 size in octal, 11 chars + space
 *   136..147 mtime in octal
 *   148..155 checksum (sum of all header bytes treating header as
 *            unsigned with the checksum field filled with spaces;
 *            written as 6 octal digits + null + space)
 *   156      typeflag '0' for regular file
 *   257..262 magic "ustar "
 *   263..264 version "00"
 *   File content follows the header, padded to a 512-byte boundary.
 */
class TarZstdArchiver : BackupArchiver {
    override fun archive(sourceDir: Path, destFile: Path, level: Int): Long =
        TODO("Implement: stream USTAR-format tar wrapped in ZstdOutputStream to destFile")
}
