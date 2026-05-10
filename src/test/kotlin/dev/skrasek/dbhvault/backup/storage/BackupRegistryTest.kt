package dev.skrasek.dbhvault.backup.storage

import dev.skrasek.dbhvault.tempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant

class BackupRegistryTest {

    // ---- Happy paths ----

    @Test
    fun `lists scheduled and pinned backups newest-first`() {
        val dir = tempDir()
        listOf(
            "world-2026-05-09T03-00-00Z.tar.zst",
            "world-2026-05-08T03-00-00Z.tar.zst",
            "world-2026-05-07T03-00-00Z--pre-update.tar.zst",
        ).forEach { dir.resolve(it).toFile().createNewFile() }

        val all = BackupRegistry(dir).list()

        assertEquals(3, all.size)
        assertEquals(Instant.parse("2026-05-09T03:00:00Z"), all[0].metadata.timestamp)
        assertEquals(Instant.parse("2026-05-08T03:00:00Z"), all[1].metadata.timestamp)
        assertEquals(Instant.parse("2026-05-07T03:00:00Z"), all[2].metadata.timestamp)
    }

    @Test
    fun `populates sizeBytes from actual file size`() {
        val dir = tempDir()
        val payload = ByteArray(4096) { it.toByte() }
        Files.write(dir.resolve("world-2026-05-09T03-00-00Z.tar.zst"), payload)

        val entry = BackupRegistry(dir).list().single()
        assertEquals(4096L, entry.sizeBytes)
    }

    @Test
    fun `populates metadata via filename parsing`() {
        val dir = tempDir()
        dir.resolve("world-2026-05-09T03-00-00Z--release.zip").toFile().createNewFile()

        val entry = BackupRegistry(dir).list().single()
        assertEquals(Instant.parse("2026-05-09T03:00:00Z"), entry.metadata.timestamp)
        assertEquals("release", entry.metadata.name)
        assertTrue(entry.metadata.isPinned)
    }

    @Test
    fun `lists mixed extensions tar zst and zip together`() {
        val dir = tempDir()
        listOf(
            "world-2026-05-09T03-00-00Z.tar.zst",
            "world-2026-05-08T03-00-00Z.zip",
        ).forEach { dir.resolve(it).toFile().createNewFile() }

        val list = BackupRegistry(dir).list()
        assertEquals(2, list.size)
    }

    @Test
    fun `mostRecent returns the newest backup`() {
        val dir = tempDir()
        listOf(
            "world-2026-05-09T03-00-00Z.tar.zst",
            "world-2026-05-07T03-00-00Z.tar.zst",
            "world-2026-05-08T03-00-00Z.tar.zst",
        ).forEach { dir.resolve(it).toFile().createNewFile() }

        val newest = BackupRegistry(dir).mostRecent()
        assertEquals(
            Instant.parse("2026-05-09T03:00:00Z"),
            newest!!.metadata.timestamp,
        )
    }

    // ---- Invalid file handling ----

    @Test
    fun `ignores files with invalid backup names`() {
        val dir = tempDir()
        dir.resolve("world-2026-05-09T03-00-00Z.tar.zst").toFile().createNewFile()
        // Junk that should not appear in the listing:
        dir.resolve("README.md").toFile().createNewFile()
        dir.resolve(".DS_Store").toFile().createNewFile()
        dir.resolve("world-malformed-name.tar.zst").toFile().createNewFile()
        dir.resolve("not-a-backup.txt").toFile().createNewFile()

        val list = BackupRegistry(dir).list()
        assertEquals(1, list.size)
        assertEquals("world-2026-05-09T03-00-00Z.tar.zst", list.first().path.fileName.toString())
    }

    @Test
    fun `ignores subdirectories and files inside them`() {
        val dir = tempDir()
        dir.resolve("world-2026-05-09T03-00-00Z.tar.zst").toFile().createNewFile()

        val sub = dir.resolve("archive")
        Files.createDirectory(sub)
        // Even though this filename is a valid backup name, it's inside a
        // subdirectory and must be ignored — registry is top-level only.
        sub.resolve("world-2026-05-08T03-00-00Z.tar.zst").toFile().createNewFile()

        val list = BackupRegistry(dir).list()
        assertEquals(1, list.size, "only top-level backup should be listed; got ${list.map { it.path.fileName }}")
    }

    // ---- Empty / missing directory ----

    @Test
    fun `returns empty list when backupDir does not exist`() {
        val parent = tempDir()
        val nonexistent = parent.resolve("never-created")

        val list = BackupRegistry(nonexistent).list()
        assertEquals(emptyList<BackupEntry>(), list)
    }

    @Test
    fun `returns empty list when backupDir is empty`() {
        val dir = tempDir()
        val list = BackupRegistry(dir).list()
        assertEquals(emptyList<BackupEntry>(), list)
    }

    @Test
    fun `returns empty list when backupDir is not a directory`() {
        // Misconfiguration: operator pointed backupDirectory at a file.
        // Defensive behavior: empty list, not crash.
        val dir = tempDir()
        val notADir = dir.resolve("oops.txt")
        Files.writeString(notADir, "I'm a file")

        val list = BackupRegistry(notADir).list()
        assertEquals(emptyList<BackupEntry>(), list)
    }

    @Test
    fun `mostRecent returns null when directory is empty`() {
        val dir = tempDir()
        assertNull(BackupRegistry(dir).mostRecent())
    }

    @Test
    fun `mostRecent returns null when only invalid files exist`() {
        val dir = tempDir()
        dir.resolve("README.md").toFile().createNewFile()
        dir.resolve(".DS_Store").toFile().createNewFile()

        assertNull(BackupRegistry(dir).mostRecent())
    }
}
