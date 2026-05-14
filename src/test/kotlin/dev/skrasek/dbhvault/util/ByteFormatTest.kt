package dev.skrasek.dbhvault.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ByteFormatTest {

    @Test
    fun `zero bytes formats as raw bytes`() {
        assertEquals("0 B", humanBytes(0L))
    }

    @Test
    fun `sub-KiB values keep the B suffix`() {
        assertEquals("1023 B", humanBytes(1023L))
    }

    @Test
    fun `exactly 1024 bytes is 1 KiB`() {
        assertEquals("1 KiB", humanBytes(1024L))
    }

    @Test
    fun `round MiB strips trailing zero`() {
        // 5 * 1024 * 1024 bytes -> "5 MiB", not "5.0 MiB".
        assertEquals("5 MiB", humanBytes(5L * 1024 * 1024))
    }

    @Test
    fun `non-round MiB keeps one decimal`() {
        // 1.5 MiB
        assertEquals("1.5 MiB", humanBytes(1024L * 1024 * 3 / 2))
    }

    @Test
    fun `value scales to GiB when it would otherwise be thousands of MiB`() {
        // 2 GiB exactly — this is the case from the original request:
        // "instead of ~2000 MB, say 2 GB".
        assertEquals("2 GiB", humanBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `value scales to TiB at the next boundary`() {
        assertEquals("1 TiB", humanBytes(1L shl 40))
    }

    @Test
    fun `petabyte-scale values use PiB and do not overflow the unit table`() {
        assertEquals("1 PiB", humanBytes(1L shl 50))
        // 1024 PiB should still render in PiB since we run out of units.
        assertEquals("1024 PiB", humanBytes(1L shl 60))
    }
}
