package com.n7folder.player.ui.common

import java.text.NumberFormat
import java.util.Locale

fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n.toLong())

/** "0 piste", "1 piste", "12 pistes" (pluriel français à partir de 2). */
fun plural(n: Int, singular: String): String =
    formatCount(n) + " " + (if (n > 1) singular + "s" else singular)

/** Durée d'une analyse : "12,3 s" ou "2 min 05 s". */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000.0
    if (totalSeconds < 60.0) return String.format(Locale.getDefault(), "%.1f s", totalSeconds)
    val minutes = (ms / 60000L).toInt()
    val seconds = ((ms % 60000L) / 1000L).toInt()
    return String.format(Locale.getDefault(), "%d min %02d s", minutes, seconds)
}

/** Position de lecture : "3:07" ou "1:02:45". */
fun formatTime(ms: Long): String {
    val totalSeconds = (if (ms < 0L) 0L else ms) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
