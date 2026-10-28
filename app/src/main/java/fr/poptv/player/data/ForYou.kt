package fr.poptv.player.data

import kotlinx.serialization.Serializable

@Serializable
data class ForYouCache(val movies: List<VodItem> = emptyList(), val series: List<VodItem> = emptyList())

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

    /**
     * Poids d'un titre de départ selon le comportement : ma liste (+3), terminé (+2), repris et bien avancé
     * (+2), un peu avancé (+1), abandonné très tôt (−2,5). Les titres récents comptent plus que les anciens.
     */
    fun seedWeight(favorite: Boolean, watched: Boolean, progress: Float?, ageDays: Float?): Float {
        var w = 0f
        if (favorite) w += 3f
        if (watched) w += 2f
        if (progress != null) w += when {
            progress >= 0.5f -> 2f
            progress >= 0.1f -> 1f
            else -> -2.5f
        }
        val recency = if (ageDays == null) 1f else (1f / (1f + ageDays / 45f)).coerceAtLeast(0.3f)
        return w * recency
    }

    /**
     * Classe les titres recommandés par TMDB : un titre recommandé par plusieurs titres de départ,
     * ou par un titre à fort poids, ou en tête de liste, remonte. La note TMDB départage.
     */
    fun rank(recsBySeed: List<Pair<Float, List<TmdbTitle>>>): List<TmdbTitle> {
        val scores = LinkedHashMap<String, Pair<TmdbTitle, Float>>()
        for ((weight, recs) in recsBySeed) {
            if (weight <= 0f) continue
            recs.forEachIndexed { position, t ->
                val k = t.title + "|" + t.year
                val gain = weight * (1f - position / (recs.size + 5f)) + (t.rating?.toFloat() ?: 5f) / 10f * 0.5f
                val old = scores[k]
                scores[k] = t to ((old?.second ?: 0f) + gain)
            }
        }
        return scores.values.sortedByDescending { it.second }.map { it.first }
    }
}
