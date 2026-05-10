package dev.skrasek.dbhvault.backup.archive

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.fileSize
import kotlin.io.path.relativeTo

/**
 * Stub awaiting implementation.
 *
 * Test contract: `src/test/kotlin/dev/skrasek/dbhvault/backup/archive/ZipArchiverTest.kt`
 *
 * Highlights from the test suite:
 *  - Walk [sourceDir] for every regular file; nested paths must be preserved
 *    relative to [sourceDir] using forward slashes (`region/r.0.0.mca`, not
 *    `region\r.0.0.mca`).
 *  - File contents are streamed byte-for-byte (no charset conversion).
 *  - Zero-byte files are archived as zero-byte entries.
 *  - Empty source directory produces an empty but structurally valid zip
 *    (i.e., `ZipFile(dest)` constructor succeeds).
 *  - Honor [level] via `ZipOutputStream.setLevel(level)`. Lower levels
 *    produce larger archives for compressible data — verified by a
 *    comparison test.
 *  - Return the byte size of the resulting file (equivalent to `Files.size(destFile)`).
 *  - Existing destination files are replaced.
 *  - Throw if [sourceDir] does not exist or is not a directory — the caller
 *    is expected to pass a valid world directory; failing fast prevents
 *    surprises like silently archiving zero files.
 *
 * Suggested approach: `ZipOutputStream(Files.newOutputStream(destFile))`,
 * `Files.walk(sourceDir)`, filter to regular files, write each as a
 * `ZipEntry` whose name is `file.relativeTo(sourceDir).toString().replace('\\', '/')`.
 */
class ZipArchiver : BackupArchiver {
    override fun archive(sourceDir: Path, destFile: Path, level: Int): Long {
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(destFile))).use { zip ->
            zip.setLevel(level.coerceIn(Deflater.NO_COMPRESSION, Deflater.BEST_COMPRESSION))

            Files.walk(sourceDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        val entryName = file.relativeTo(sourceDir).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
            }
        }

        return destFile.fileSize()
    }
}
