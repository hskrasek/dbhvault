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
 * Streams a directory tree into a `.zip` file. Entry names use forward slashes
 * regardless of platform so archives produced on Windows extract correctly
 * elsewhere.
 *
 * Throws if [sourceDir] does not exist or is not a directory — failing fast
 * prevents accidental zero-entry archives from caller misuse.
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
