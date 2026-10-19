package fr.poptv.player.ui

/** 3725000 ms -> "1:02:05", 125000 ms -> "2:05". */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Les serveurs renvoient souvent « 0 » ou une chaîne vide quand il n'y a pas de note. */
internal fun cleanRating(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val number = value.replace(',', '.').toDoubleOrNull()
    return if (number != null && number <= 0.0) null else value
}

/** "00:45:12" -> "45 min", "01:05:00" -> "1 h 05". Les autres formats sont laissés tels quels. */
internal fun prettyDuration(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == "0" || value == "00:00:00") return null
    val parts = value.split(":").map { it.toIntOrNull() ?: return value }
    if (parts.size != 3) return value
    val totalMinutes = parts[0] * 60 + parts[1] + if (parts[2] >= 30) 1 else 0
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        totalMinutes <= 0 -> null
        h == 0 -> "$m min"
        else -> "%d h %02d".format(h, m)
    }
}

/** Heure locale d'un horodatage Unix (secondes), par exemple « 20:45 ». */
internal fun formatClock(sec: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))
