package dev.skrasek.dbhvault.util

import java.util.Locale

private const val KIB = 1024.0
private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

/**
 * Renders a byte count using the largest IEC binary unit (KiB, MiB, GiB, …)
 * that keeps the value below 1024. Values < 1 KiB are shown as whole bytes;
 * everything else gets one decimal of precision with a trailing ".0" stripped
 * so a round 2 GiB reads as "2 GiB" rather than "2.0 GiB".
 */
fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var i = 0
    while (value >= KIB && i < UNITS.size - 1) {
        value /= KIB
        i++
    }
    val formatted = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    return "$formatted ${UNITS[i]}"
}
