package dev.skrasek.dbhvault.backup

import dev.skrasek.dbhvault.config.ArchiveFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BackupNamingTest {

    private val instant = Instant.parse("2026-05-09T03:00:00Z")

    // ---- Happy paths ----

    @Test
    fun `scheduled backup has no name suffix`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z.tar.zst",
            BackupNaming.fileName(instant, name = null, format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `named backup includes double-dash separated name`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z--pre-update.tar.zst",
            BackupNaming.fileName(instant, name = "pre-update", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `zip format uses zip extension`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z.zip",
            BackupNaming.fileName(instant, name = null, format = ArchiveFormat.ZIP),
        )
    }

    @Test
    fun `zip format with name uses zip extension`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z--manual.zip",
            BackupNaming.fileName(instant, name = "manual", format = ArchiveFormat.ZIP),
        )
    }

    // ---- Sanitization edges ----

    @Test
    fun `name with spaces is replaced with dashes`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z--my-cool-backup.tar.zst",
            BackupNaming.fileName(instant, name = "my cool backup", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `name with path separators is sanitized`() {
        // / and \ would let a malicious name escape the backups directory.
        // Both must be neutralized.
        assertEquals(
            "world-2026-05-09T03-00-00Z--evil-etc-passwd.tar.zst",
            BackupNaming.fileName(instant, name = "evil/etc/passwd", format = ArchiveFormat.TAR_ZST),
        )
        assertEquals(
            "world-2026-05-09T03-00-00Z--evil-windows-system32.tar.zst",
            BackupNaming.fileName(instant, name = "evil\\windows\\system32", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `name with leading and trailing dashes is trimmed`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z--core.tar.zst",
            BackupNaming.fileName(instant, name = "---core---", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `consecutive special characters collapse rather than stack`() {
        // "a   b" must not become "a---b" with three dashes; collapse to one.
        assertEquals(
            "world-2026-05-09T03-00-00Z--a-b.tar.zst",
            BackupNaming.fileName(instant, name = "a   b", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `underscore in name is preserved`() {
        // _ is filesystem-safe and worth keeping for human-readable names.
        assertEquals(
            "world-2026-05-09T03-00-00Z--release_v2.tar.zst",
            BackupNaming.fileName(instant, name = "release_v2", format = ArchiveFormat.TAR_ZST),
        )
    }

    // ---- Unhappy paths ----

    @Test
    fun `empty name string produces no suffix`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z.tar.zst",
            BackupNaming.fileName(instant, name = "", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `whitespace-only name produces no suffix`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z.tar.zst",
            BackupNaming.fileName(instant, name = "   ", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `name of only special characters produces no suffix`() {
        // After sanitize+trim, "///" -> "" -> treated as null.
        assertEquals(
            "world-2026-05-09T03-00-00Z.tar.zst",
            BackupNaming.fileName(instant, name = "///", format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `name of only dashes produces no suffix`() {
        assertEquals(
            "world-2026-05-09T03-00-00Z.tar.zst",
            BackupNaming.fileName(instant, name = "------", format = ArchiveFormat.TAR_ZST),
        )
    }

    // ---- Timestamp edges ----

    @Test
    fun `sub-second precision is truncated to whole seconds`() {
        // Filenames must not contain milliseconds; the format only carries seconds.
        val withMillis = Instant.parse("2026-05-09T03:00:00.987Z")
        assertEquals(
            "world-2026-05-09T03-00-00Z.tar.zst",
            BackupNaming.fileName(withMillis, name = null, format = ArchiveFormat.TAR_ZST),
        )
    }

    @Test
    fun `timestamp is always formatted in UTC regardless of input`() {
        // Instant has no zone, but two equal Instants must produce identical filenames
        // even when constructed via different zoned representations.
        val viaUtc = Instant.parse("2026-05-09T15:30:45Z")
        val viaTokyo = java.time.OffsetDateTime.parse("2026-05-10T00:30:45+09:00").toInstant()
        assertEquals(viaUtc, viaTokyo)
        assertEquals(
            BackupNaming.fileName(viaUtc, null, ArchiveFormat.TAR_ZST),
            BackupNaming.fileName(viaTokyo, null, ArchiveFormat.TAR_ZST),
        )
    }
}
