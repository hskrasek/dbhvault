package dev.skrasek.dbhvault.util

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Builders for the mod's player-visible chat text. Two house rules:
 * - any mention of the mod name renders in [ChatFormatting.GREEN]
 * - byte sizes (from [humanBytes]) render in [ChatFormatting.BOLD] so they
 *   stay legible when the chat scroller is busy.
 */
object Messages {

    /** Bracketed prefix used by Notifier broadcasts: "[DBHVault]" in green. */
    fun brandPrefix(): MutableComponent =
        Component.literal("[DBHVault]").withStyle(ChatFormatting.GREEN)

    /** Inline mention of the mod name in green, for command output headers. */
    fun brand(): MutableComponent =
        Component.literal("DBHVault").withStyle(ChatFormatting.GREEN)

    /** A humanized byte size rendered in bold (e.g. **2 GiB**). */
    fun size(bytes: Long): MutableComponent =
        Component.literal(humanBytes(bytes)).withStyle(ChatFormatting.BOLD)
}
