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
