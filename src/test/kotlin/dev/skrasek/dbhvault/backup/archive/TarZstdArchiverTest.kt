package dev.skrasek.dbhvault.backup.archive

import com.github.luben.zstd.ZstdInputStream
import dev.skrasek.dbhvault.tempDir
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class TarZstdArchiverTest {

    // ---- Happy paths ----

    @Test
    fun `archives a flat directory and round-trips file content`() {
        val src = tempDir("tarzst-flat-src-")
        Files.writeString(src.resolve("level.dat"), "world-level-data")

        val dest = tempDir("tarzst-flat-dest-").resolve("out.tar.zst")
        val size = TarZstdArchiver().archive(src, dest, level = 3)

        assertTrue(size > 0L, "expected non-empty archive, got $size bytes")
        val entries = readTarZst(dest)
        val content = entries.firstOrNull { it.first == "level.dat" }?.second
        assertNotNull(content, "level.dat should be in archive; got ${entries.map { it.first }}")
        assertEquals("world-level-data", String(content!!, StandardCharsets.UTF_8))
    }

    @Test
    fun `archives nested directory preserving relative paths`() {
        val src = tempDir("tarzst-nested-src-")
        Files.writeString(src.resolve("level.dat"), "level")

        val region = src.resolve("region")
        Files.createDirectory(region)
        Files.writeString(region.resolve("r.0.0.mca"), "region")

        val nether = src.resolve("DIM-1").resolve("region")
        Files.createDirectories(nether)
        Files.writeString(nether.resolve("r.-1.0.mca"), "nether")

        val dest = tempDir("tarzst-nested-dest-").resolve("out.tar.zst")
        TarZstdArchiver().archive(src, dest, level = 3)

        val names = readTarZst(dest).map { it.first }.sorted()
        assertEquals(
            listOf("DIM-1/region/r.-1.0.mca", "level.dat", "region/r.0.0.mca"),
            names,
        )
    }

    @Test
    fun `empty source directory produces a valid empty tar zst`() {
        val src = tempDir("tarzst-empty-src-")
        val dest = tempDir("tarzst-empty-dest-").resolve("out.tar.zst")

        TarZstdArchiver().archive(src, dest, level = 3)

        // readTarZst will throw if zstd-decompress or tar-parse fails — those
        // failures are the load-bearing assertion. Zero entries confirms it's empty.
        val entries = readTarZst(dest)
        assertEquals(0, entries.size)
    }

    @Test
    fun `returns the byte size of the resulting archive`() {
        val src = tempDir("tarzst-size-src-")
        Files.writeString(src.resolve("hello.txt"), "hello world")

        val dest = tempDir("tarzst-size-dest-").resolve("out.tar.zst")
        val returned = TarZstdArchiver().archive(src, dest, level = 3)

        assertEquals(Files.size(dest), returned)
        assertTrue(returned > 0L)
    }

    // ---- Behavior / contract ----

    @Test
    fun `binary content is preserved byte-for-byte`() {
        val src = tempDir("tarzst-binary-src-")
        val payload = byteArrayOf(0x00, 0xFF.toByte(), 0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x0D, 0x0A, 0x7F)
        Files.write(src.resolve("data.bin"), payload)

        val dest = tempDir("tarzst-binary-dest-").resolve("out.tar.zst")
        TarZstdArchiver().archive(src, dest, level = 3)

        val read = readTarZst(dest).single { it.first == "data.bin" }.second
        assertArrayEquals(payload, read)
    }

    @Test
    fun `zero-byte files are archived as zero-byte entries`() {
        val src = tempDir("tarzst-empty-file-src-")
        Files.createFile(src.resolve("empty.txt"))

        val dest = tempDir("tarzst-empty-file-dest-").resolve("out.tar.zst")
        TarZstdArchiver().archive(src, dest, level = 3)

        val entries = readTarZst(dest)
        val empty = entries.single { it.first == "empty.txt" }
        assertEquals(0, empty.second.size)
    }

    @Test
    fun `compression level affects archive size for compressible data`() {
        val src = tempDir("tarzst-compress-src-")
        // 64KB of repeating bytes — highly compressible.
        val payload = "0123456789".repeat(6500).toByteArray()
        Files.write(src.resolve("repeats.txt"), payload)

        val destDir = tempDir("tarzst-compress-dest-")
        // zstd levels: 1 = fastest, 22 = max. Use 1 vs 19 for a meaningful gap
        // without overwhelming test runtime at 22.
        val fastSize = TarZstdArchiver().archive(src, destDir.resolve("fast.tar.zst"), level = 1)
        val maxSize = TarZstdArchiver().archive(src, destDir.resolve("max.tar.zst"), level = 19)

        assertTrue(
            fastSize >= maxSize,
            "level=1 should produce an archive at least as large as level=19 for compressible data " +
                "(fast=$fastSize, max=$maxSize)",
        )
    }

    @Test
    fun `tar entry names always use forward slashes`() {
        val src = tempDir("tarzst-slash-src-")
        val nested = src.resolve("region")
        Files.createDirectory(nested)
        Files.writeString(nested.resolve("r.0.0.mca"), "data")

        val dest = tempDir("tarzst-slash-dest-").resolve("out.tar.zst")
        TarZstdArchiver().archive(src, dest, level = 3)

        val names = readTarZst(dest).map { it.first }
        assertTrue(
            names.contains("region/r.0.0.mca"),
            "expected region/r.0.0.mca; got $names",
        )
        assertFalse(
            names.any { it.contains('\\') },
            "no backslashes allowed in entry names; got $names",
        )
    }

    @Test
    fun `existing destination file is overwritten`() {
        val src = tempDir("tarzst-overwrite-src-")
        Files.writeString(src.resolve("a.txt"), "fresh")

        val dest = tempDir("tarzst-overwrite-dest-").resolve("out.tar.zst")
        Files.writeString(dest, "old content that should be replaced")

        TarZstdArchiver().archive(src, dest, level = 3)

        val content = readTarZst(dest).single { it.first == "a.txt" }.second
        assertEquals("fresh", String(content, StandardCharsets.UTF_8))
    }

    @Test
    fun `UTF-8 filenames are preserved`() {
        // USTAR doesn't formally specify a charset, but most modern tar readers
        // (including commons-compress) decode entry names as UTF-8.
        val src = tempDir("tarzst-utf8-src-")
        val name = "café-data.txt"
        Files.writeString(src.resolve(name), "data", StandardCharsets.UTF_8)

        val dest = tempDir("tarzst-utf8-dest-").resolve("out.tar.zst")
        TarZstdArchiver().archive(src, dest, level = 3)

        val names = readTarZst(dest).map { it.first }
        assertTrue(names.contains(name), "expected $name in archive; got $names")
    }

    // ---- Format-specific limits ----

    @Test
    fun `throws when filename exceeds 100-char USTAR limit`() {
        // USTAR's name field is 100 bytes. Longer names need PAX/GNU
        // extensions which are out of scope for v1. Must fail loudly,
        // not silently truncate.
        val src = tempDir("tarzst-longname-src-")
        val longName = "a".repeat(101) + ".txt"
        Files.writeString(src.resolve(longName), "data")

        val dest = tempDir("tarzst-longname-dest-").resolve("out.tar.zst")
        assertThrows<Exception> {
            TarZstdArchiver().archive(src, dest, level = 3)
        }
    }

    // ---- Unhappy paths ----

    @Test
    fun `throws when source directory does not exist`() {
        val nonexistent = tempDir("tarzst-missing-").resolve("does-not-exist")
        val dest = tempDir("tarzst-missing-dest-").resolve("out.tar.zst")

        assertThrows<Exception> {
            TarZstdArchiver().archive(nonexistent, dest, level = 3)
        }
    }

    @Test
    fun `throws when source path is a regular file`() {
        val src = tempDir("tarzst-not-a-dir-")
        val regularFile = src.resolve("not-a-dir.txt")
        Files.writeString(regularFile, "I'm a file, not a directory")
        val dest = tempDir("tarzst-not-a-dir-dest-").resolve("out.tar.zst")

        assertThrows<Exception> {
            TarZstdArchiver().archive(regularFile, dest, level = 3)
        }
    }

    // ---- Helper ----

    /** Decompresses [file] (zstd) and reads it as a tar archive, returning [(name, bytes), ...]. */
    private fun readTarZst(file: Path): List<Pair<String, ByteArray>> {
        val results = mutableListOf<Pair<String, ByteArray>>()
        ZstdInputStream(Files.newInputStream(file)).use { zstd ->
            TarArchiveInputStream(zstd).use { tar ->
                generateSequence { tar.nextEntry }.forEach { entry ->
                    if (!entry.isDirectory) {
                        results.add(entry.name to tar.readAllBytes())
                    }
                }
            }
        }
        return results
    }
}
