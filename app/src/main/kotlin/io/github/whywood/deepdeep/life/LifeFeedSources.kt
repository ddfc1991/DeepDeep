package io.github.whywood.deepdeep.life

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LifeFeedSource(
    val id: String,
    val label: String,
    val url: String,
    val tags: List<String> = emptyList(),
)

fun defaultLifeFeedSources(): List<LifeFeedSource> = listOf(
    LifeFeedSource("v2ex", "V2EX", "https://www.v2ex.com/index.xml", listOf("forum", "tech", "programming")),
    LifeFeedSource("hackernews", "Hacker News", "https://hnrss.org/frontpage", listOf("tech", "news", "startup")),
    LifeFeedSource("sspai", "少数派", "https://sspai.com/feed", listOf("mobile", "app", "productivity")),
    LifeFeedSource(
        "gittrending",
        "GitHub Trending",
        "https://mshibanami.github.io/GitHubTrendingRSS/daily/all.xml",
        listOf("github", "opensource", "code"),
    ),
)

fun loadConfiguredSources(file: File): List<LifeFeedSource> {
    if (!file.isFile) return defaultLifeFeedSources()
    val text = runCatching { file.readText().trim() }.getOrNull() ?: return defaultLifeFeedSources()
    if (!text.startsWith('[')) return defaultLifeFeedSources()
    return runCatching {
        val elements = JSONArray(text)
        val sources = mutableListOf<LifeFeedSource>()
        for (i in 0 until elements.length()) {
            val raw = elements.optJSONObject(i) ?: continue
            val url = raw.optString("url").trim()
            if (!url.startsWith("https://")) continue
            val tags = mutableListOf<String>()
            raw.optJSONArray("tags")?.let { array ->
                for (j in 0 until array.length()) {
                    val tag = array.optString(j).trim()
                    if (tag.isNotEmpty()) tags += tag
                }
            }
            sources += LifeFeedSource(
                id = raw.optString("id").trim().ifBlank { url },
                label = raw.optString("label").trim().ifBlank { hostOf(url) },
                url = url,
                tags = tags,
            )
        }
        sources
    }.getOrElse { defaultLifeFeedSources() }
}

fun pickLifeFeedSources(
    sources: List<LifeFeedSource>,
    interests: List<Interest>,
    cooldownUntil: Map<String, Long>,
    now: Long,
    limit: Int,
): List<LifeFeedSource> {
    if (sources.isEmpty() || limit <= 0) return emptyList()
    val free = sources.filter { (cooldownUntil[it.id] ?: 0L) <= now }
    val pool = if (free.isEmpty()) sources else free
    return pool
        .map { source -> source to interestAffinity(source, interests) }
        .sortedByDescending { (_, affinity) -> affinity }
        .map { (source, _) -> source }
        .take(limit)
}

fun interestAffinity(source: LifeFeedSource, interests: List<Interest>): Double {
    var affinity = 0.0
    for (interest in interests) {
        val topic = interest.topic.trim()
        if (topic.isEmpty()) continue
        val relevant = source.label.contains(topic) ||
            source.tags.any { tag -> topic.contains(tag) || tag.contains(topic) }
        if (relevant) affinity += interest.strength
    }
    return affinity.coerceAtMost(8.0)
}

fun hostOf(url: String): String =
    url.substringAfter("://", "").substringBefore('/').substringBefore(':')
