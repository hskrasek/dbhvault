package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BackupMetadataTest {

    // ---- Happy paths ----

    @Test
    fun `parses scheduled tar zst backup`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z.tar.zst")!!
        assertEquals(Instant.parse("2026-05-09T03:00:00Z"), meta.timestamp)
        assertNull(meta.name)
        assertEquals(ArchiveFormat.TAR_ZST, meta.format)
        assertFalse(meta.isPinned)
    }

    @Test
    fun `parses named backup as pinned`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z--pre-update.tar.zst")!!
        assertEquals(Instant.parse("2026-05-09T03:00:00Z"), meta.timestamp)
        assertEquals("pre-update", meta.name)
        assertTrue(meta.isPinned)
    }

    @Test
    fun `parses zip extension`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z.zip")!!
        assertEquals(ArchiveFormat.ZIP, meta.format)
        assertNull(meta.name)
    }

    @Test
    fun `parses named zip backup`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z--snapshot.zip")!!
        assertEquals(ArchiveFormat.ZIP, meta.format)
        assertEquals("snapshot", meta.name)
    }

    @Test
    fun `name with underscores parses correctly`() {
        val meta = BackupMetadata.parse("world-2026-05-09T03-00-00Z--release_v2_final.tar.zst")!!
        assertEquals("release_v2_final", meta.name)
    }

    // ---- isPinned derivation ----

    @Test
    fun `isPinned is true when name is non-null`() {
        val pinned = BackupMetadata(Instant.now(), "anything", ArchiveFormat.TAR_ZST)
        val scheduled = BackupMetadata(Instant.now(), null, ArchiveFormat.TAR_ZST)
        assertTrue(pinned.isPinned)
        assertFalse(scheduled.isPinned)
    }

    // ---- Unhappy paths ----

    @Test
    fun `returns null for non-matching filename`() {
        assertNull(BackupMetadata.parse("random-file.txt"))
        assertNull(BackupMetadata.parse("README.md"))
        assertNull(BackupMetadata.parse(".DS_Store"))
    }

    @Test
    fun `returns null when prefix is missing`() {
        assertNull(BackupMetadata.parse("2026-05-09T03-00-00Z.tar.zst"))
    }

    @Test
    fun `returns null when prefix differs in case`() {
        // Case-sensitive parsing — World/WORLD must not be accepted as world.
        assertNull(BackupMetadata.parse("World-2026-05-09T03-00-00Z.tar.zst"))
        assertNull(BackupMetadata.parse("WORLD-2026-05-09T03-00-00Z.tar.zst"))
    }

    @Test
    fun `returns null when timestamp regex does not match`() {
        assertNull(BackupMetadata.parse("world-not-a-date.tar.zst"))
        assertNull(BackupMetadata.parse("world-2026-05-09.tar.zst"))           // missing time
        assertNull(BackupMetadata.parse("world-2026-05-09T03:00:00Z.tar.zst")) // colons not dashes
    }

    @Test
    fun `returns null when timestamp values are invalid`() {
        // The regex matches but the date itself is invalid — must not throw, must return null.
        assertNull(BackupMetadata.parse("world-2026-13-09T03-00-00Z.tar.zst")) // month 13
        assertNull(BackupMetadata.parse("world-2026-05-32T03-00-00Z.tar.zst")) // day 32
        assertNull(BackupMetadata.parse("world-2026-05-09T25-00-00Z.tar.zst")) // hour 25
    }

    @Test
    fun `returns null when extension is unsupported`() {
        assertNull(BackupMetadata.parse("world-2026-05-09T03-00-00Z.tar.gz"))
        assertNull(BackupMetadata.parse("world-2026-05-09T03-00-00Z.7z"))
        assertNull(BackupMetadata.parse("world-2026-05-09T03-00-00Z"))         // no extension
    }

    @Test
    fun `returns null for empty filename`() {
        assertNull(BackupMetadata.parse(""))
    }

    @Test
    fun `returns null when name contains whitespace`() {
        // Names emitted by BackupNaming are pre-sanitized; raw whitespace in a name
        // means someone hand-edited the file or something else is afoot.
        assertNull(BackupMetadata.parse("world-2026-05-09T03-00-00Z--has space.tar.zst"))
    }

    @Test
    fun `returns null when name contains path separators`() {
        // Defense in depth: even if a malformed file lands in the dir, we don't
        // emit a BackupMetadata claiming a path-traversal name is valid.
        assertNull(BackupMetadata.parse("world-2026-05-09T03-00-00Z--evil/path.tar.zst"))
    }

    @Test
    fun `returns null when name is empty between dashes`() {
        // "world-T--.tar.zst" — empty name section is malformed.
        assertNull(BackupMetadata.parse("world-2026-05-09T03-00-00Z--.tar.zst"))
    }

    // ---- Roundtrip ----

    @Test
    fun `parse roundtrips with BackupNaming fileName for clean inputs`() {
        val instant = Instant.parse("2026-05-09T03:00:00Z")
        val cases = listOf(
            Triple(instant, null, ArchiveFormat.TAR_ZST),
            Triple(instant, "release", ArchiveFormat.TAR_ZST),
            Triple(instant, "release_v2", ArchiveFormat.ZIP),
            Triple(instant, null, ArchiveFormat.ZIP),
        )
        for ((ts, name, fmt) in cases) {
            val fileName = BackupNaming.fileName(ts, name, fmt)
            val parsed = BackupMetadata.parse(fileName)!!
            assertEquals(ts, parsed.timestamp, "timestamp roundtrip for $fileName")
            assertEquals(name, parsed.name, "name roundtrip for $fileName")
            assertEquals(fmt, parsed.format, "format roundtrip for $fileName")
        }
    }
}
