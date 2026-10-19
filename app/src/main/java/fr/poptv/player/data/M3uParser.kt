package fr.poptv.player.data

object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(lines: Sequence<String>): Playlist {
        val channels = ArrayList<Channel>()
        val groups = LinkedHashSet<String>()
        val usedIds = HashSet<String>()

        var name: String? = null
        var logo: String? = null
        var group = DEFAULT_GROUP
        var epgId: String? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF")) {
                val attrs = attrRegex.findAll(line)
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP
                epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                name = extinfName(line)
                    .ifEmpty { attrs["tvg-name"].orEmpty() }
                    .ifEmpty { "Chaîne ${channels.size + 1}" }
            } else if (!line.startsWith("#")) {
                val current = name
                if (current != null) {
                    groups.add(group)
                    // Identifiant unique, même si la même URL apparaît plusieurs fois.
                    var id = line.hashCode().toString()
                    while (!usedIds.add(id)) id += "_"
                    channels.add(
                        Channel(
                            id = id,
                            name = current,
                            streamUrl = line,
                            logo = logo,
                            categoryId = group,
                            epgId = epgId,
                        )
                    )
                }
                name = null
            }
        }

        return Playlist(
            categories = groups.map { Category(id = it, name = it) },
            channels = channels,
        )
    }

    /** Le nom est ce qui suit la première virgule hors guillemets. */
    private fun extinfName(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(i + 1).trim()
        }
        return ""
    }

    private const val DEFAULT_GROUP = "Divers"
}
