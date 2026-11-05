package com.offlinechat.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** Group colours the creator can pick from. Stored as ARGB ints in the database. */
val GroupColors: List<Int> = listOf(
    0xFF0F766E.toInt(), 0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(),
    0xFFEA580C.toInt(), 0xFF65A30D.toInt(), 0xFF0891B2.toInt(), 0xFF475569.toInt(),
)

private val SenderLight = listOf(
    Color(0xFF0F766E), Color(0xFF2563EB), Color(0xFF7C3AED), Color(0xFFBE185D),
    Color(0xFFC2410C), Color(0xFF4D7C0F), Color(0xFF0E7490), Color(0xFFA16207),
)
private val SenderDark = listOf(
    Color(0xFF5EEAD4), Color(0xFF93C5FD), Color(0xFFC4B5FD), Color(0xFFF9A8D4),
    Color(0xFFFDBA74), Color(0xFFBEF264), Color(0xFF67E8F9), Color(0xFFFDE047),
)

/** A stable colour per person, readable on both light and dark bubbles. */
fun senderColor(peerId: Long, dark: Boolean): Color {
    val list = if (dark) SenderDark else SenderLight
    return list[(abs(peerId % list.size)).toInt()]
}

fun colorFromArgb(argb: Int): Color = Color(argb)

/** Avatar backgrounds always carry white text, so they use the deeper light-theme shades in both themes. */
fun avatarColor(id: Long): Color = SenderLight[(abs(id % SenderLight.size)).toInt()]
