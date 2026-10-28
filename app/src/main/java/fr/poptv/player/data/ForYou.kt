package fr.poptv.player.data

/**
 * Suggestions « Pour vous » : on repère les catégories (et l'époque) des titres que l'utilisateur a
 * ajoutés à sa liste, repris ou vus, puis on propose d'autres titres du catalogue qui s'en rapprochent,
 * en écartant ceux déjà vus ou déjà dans sa liste. Simple et hors ligne : aucune donnée n'est envoyée.
 */
object ForYou {
    const val MIN_SEEDS = 3
    private const val LIMIT = 20
    private const val PER_CATEGORY = 8

    fun compute(catalog: List<VodItem>, seeds: List<VodItem>, excludedIds: Set<String>): List<VodItem> {
        if (seeds.size < MIN_SEEDS || catalog.isEmpty()) return emptyList()

        val weights = HashMap<String, Int>()
        for (s in seeds) if (s.categoryId.isNotEmpty()) weights.merge(s.categoryId, 1, Int::plus)
        if (weights.isEmpty()) return emptyList()
        val maxWeight = weights.values.max().toFloat()
        val years = seeds.mapNotNull { it.year?.toIntOrNull() }
        val avgYear = if (years.isEmpty()) null else years.average()

        val seedIds = seeds.mapTo(HashSet()) { it.id }
        val scored = ArrayList<Pair<Float, VodItem>>()
        for (item in catalog) {
            if (item.id in seedIds || item.id in excludedIds || item.poster == null) continue
            val w = weights[item.categoryId] ?: continue
            var score = w / maxWeight * 3f
            score += (item.rating?.replace(',', '.')?.toFloatOrNull()?.coerceIn(0f, 10f) ?: 5f) / 10f * 1.5f
            val y = item.year?.toIntOrNull()
            if (avgYear != null && y != null && kotlin.math.abs(y - avgYear) <= 6) score += 0.5f
            scored.add(score to item)
        }
        scored.sortWith(compareByDescending<Pair<Float, VodItem>> { it.first }.thenBy { it.second.name })

        // Pas plus de PER_CATEGORY titres d'une même catégorie : de la variété.
        val taken = HashMap<String, Int>()
        val out = ArrayList<VodItem>(LIMIT)
        for ((_, item) in scored) {
            val n = taken.getOrDefault(item.categoryId, 0)
            if (n >= PER_CATEGORY) continue
            taken[item.categoryId] = n + 1
            out.add(item)
            if (out.size >= LIMIT) break
        }
        return out
    }
}
