package com.offlinechat.app.ui.util

import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormatter)

fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dayFormatter)
}

/** For the chat list: time today, otherwise "Yesterday" or the date. */
fun listTimeLabel(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val date = localDate(epochMs)
    return if (date == LocalDate.now()) formatTime(epochMs) else dayLabel(date)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return DecimalFormat("0").format(kb) + " KB"
    return DecimalFormat("0.0").format(kb / 1024.0) + " MB"
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}
