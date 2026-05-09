package dev.skrasek.dbhvault.backup.archive

import dev.skrasek.dbhvault.tempDir
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile

class ZipArchiverTest {

    // ---- Happy paths ----

    @Test
    fun `archives a flat directory and round-trips file content`() {
        val src = tempDir("zip-flat-src-")
        Files.writeString(src.resolve("level.dat"), "world-level-data")

        val dest = tempDir("zip-flat-dest-").resolve("out.zip")
        val size = ZipArchiver().archive(src, dest, level = 6)

        assertTrue(size > 0L, "expected non-empty archive, got $size bytes")
        ZipFile(dest.toFile()).use { zip ->
            val entry = zip.getEntry("level.dat")
            assertNotNull(entry, "level.dat entry should exist")
            val content = zip.getInputStream(entry).bufferedReader().readText()
            assertEquals("world-level-data", content)
        }
    }

    @Test
    fun `archives nested directory preserving relative paths`() {
        val src = tempDir("zip-nested-src-")
        Files.writeString(src.resolve("level.dat"), "level")

        val region = src.resolve("region")
        Files.createDirectory(region)
        Files.writeString(region.resolve("r.0.0.mca"), "region")

        val nether = src.resolve("DIM-1").resolve("region")
        Files.createDirectories(nether)
        Files.writeString(nether.resolve("r.-1.0.mca"), "nether")

        val dest = tempDir("zip-nested-dest-").resolve("out.zip")
        ZipArchiver().archive(src, dest, level = 6)

        ZipFile(dest.toFile()).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(
                listOf("DIM-1/region/r.-1.0.mca", "level.dat", "region/r.0.0.mca"),
                names,
            )
        }
    }

    @Test
    fun `empty source directory produces a valid empty zip`() {
        val src = tempDir("zip-empty-src-")
        val dest = tempDir("zip-empty-dest-").resolve("out.zip")

        ZipArchiver().archive(src, dest, level = 6)

        // ZipFile constructor will throw if the file isn't a valid zip — this is
        // the load-bearing assertion. The entry count just confirms it's empty.
        ZipFile(dest.toFile()).use { zip ->
            assertEquals(0, zip.entries().toList().size)
        }
    }

    @Test
    fun `returns the byte size of the resulting archive`() {
        val src = tempDir("zip-size-src-")
        Files.writeString(src.resolve("hello.txt"), "hello world")

        val dest = tempDir("zip-size-dest-").resolve("out.zip")
        val returned = ZipArchiver().archive(src, dest, level = 6)

        assertEquals(Files.size(dest), returned)
        assertTrue(returned > 0L)
    }

    // ---- Behavior / contract ----

    @Test
    fun `binary content is preserved byte-for-byte`() {
        val src = tempDir("zip-binary-src-")
        // Bytes that would mangle under text encoding: 0x00, 0xFF, BOM-ish, CR/LF.
        val payload = byteArrayOf(0x00, 0xFF.toByte(), 0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x0D, 0x0A, 0x7F)
        Files.write(src.resolve("data.bin"), payload)

        val dest = tempDir("zip-binary-dest-").resolve("out.zip")
        ZipArchiver().archive(src, dest, level = 6)

        ZipFile(dest.toFile()).use { zip ->
            val read = zip.getInputStream(zip.getEntry("data.bin")).readAllBytes()
            assertArrayEquals(payload, read)
        }
    }

    @Test
    fun `zero-byte files are archived as zero-byte entries`() {
        val src = tempDir("zip-empty-file-src-")
        Files.createFile(src.resolve("empty.txt"))

        val dest = tempDir("zip-empty-file-dest-").resolve("out.zip")
        ZipArchiver().archive(src, dest, level = 6)

        ZipFile(dest.toFile()).use { zip ->
            val entry = zip.getEntry("empty.txt")
            assertNotNull(entry, "empty file should still be archived")
            assertEquals(0L, entry.size)
        }
    }

    @Test
    fun `compression level affects archive size for compressible data`() {
        val src = tempDir("zip-compress-src-")
        // 64KB of repeating bytes — highly compressible.
        val payload = "0123456789".repeat(6500).toByteArray()
        Files.write(src.resolve("repeats.txt"), payload)

        val destDir = tempDir("zip-compress-dest-")
        val storedSize = ZipArchiver().archive(src, destDir.resolve("stored.zip"), level = 0)
        val deflatedSize = ZipArchiver().archive(src, destDir.resolve("deflated.zip"), level = 9)

        assertTrue(
            storedSize > deflatedSize,
            "level=0 should produce a larger archive than level=9 for compressible data " +
                "(stored=$storedSize, deflated=$deflatedSize)",
        )
    }

    @Test
    fun `zip entry names always use forward slashes`() {
        // On Windows, the JDK's File API uses '\\' as separator. The archiver must
        // normalize to '/' so the archive is portable across platforms.
        val src = tempDir("zip-slash-src-")
        val nested = src.resolve("region")
        Files.createDirectory(nested)
        Files.writeString(nested.resolve("r.0.0.mca"), "data")

        val dest = tempDir("zip-slash-dest-").resolve("out.zip")
        ZipArchiver().archive(src, dest, level = 6)

        ZipFile(dest.toFile()).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(
                names.contains("region/r.0.0.mca"),
                "expected region/r.0.0.mca; got $names",
            )
            assertFalse(
                names.any { it.contains('\\') },
                "no backslashes allowed in entry names; got $names",
            )
        }
    }

    @Test
    fun `existing destination file is overwritten`() {
        val src = tempDir("zip-overwrite-src-")
        Files.writeString(src.resolve("a.txt"), "fresh")

        val dest = tempDir("zip-overwrite-dest-").resolve("out.zip")
        // Pre-existing file at the destination — must be replaced, not appended to.
        Files.writeString(dest, "old content that should be replaced")

        ZipArchiver().archive(src, dest, level = 6)

        ZipFile(dest.toFile()).use { zip ->
            val content = zip.getInputStream(zip.getEntry("a.txt")).bufferedReader().readText()
            assertEquals("fresh", content)
        }
    }

    @Test
    fun `UTF-8 filenames are preserved`() {
        // Real-world worlds occasionally have non-ASCII filenames in datapacks
        // and resource packs. Round-trip them correctly.
        val src = tempDir("zip-utf8-src-")
        val name = "café-data.txt"
        Files.writeString(src.resolve(name), "data", StandardCharsets.UTF_8)

        val dest = tempDir("zip-utf8-dest-").resolve("out.zip")
        ZipArchiver().archive(src, dest, level = 6)

        ZipFile(dest.toFile(), StandardCharsets.UTF_8).use { zip ->
            assertNotNull(zip.getEntry(name), "expected entry $name in archive")
        }
    }

    // ---- Unhappy paths ----

    @Test
    fun `throws when source directory does not exist`() {
        val nonexistent = tempDir("zip-missing-").resolve("does-not-exist")
        val dest = tempDir("zip-missing-dest-").resolve("out.zip")

        assertThrows<Exception> {
            ZipArchiver().archive(nonexistent, dest, level = 6)
        }
    }

    @Test
    fun `throws when source path is a regular file`() {
        // Calling archive() with a file (not a directory) is a programming error.
        // Failing fast prevents the implementer from accidentally producing a
        // single-entry archive that no caller actually wanted.
        val src = tempDir("zip-not-a-dir-")
        val regularFile = src.resolve("not-a-dir.txt")
        Files.writeString(regularFile, "I'm a file, not a directory")
        val dest = tempDir("zip-not-a-dir-dest-").resolve("out.zip")

        assertThrows<Exception> {
            ZipArchiver().archive(regularFile, dest, level = 6)
        }
    }
}
