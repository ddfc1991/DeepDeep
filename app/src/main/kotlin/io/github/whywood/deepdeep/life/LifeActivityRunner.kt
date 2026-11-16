package io.github.whywood.deepdeep.life

import io.github.whywood.deepdeep.R
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface LifeTexts {
    fun text(resId: Int, vararg args: Any): String
}

data class LifeActivityOutcome(
    val kind: EpisodeKind,
    val result: String,
    val headline: String,
    val items: List<LifeContentItem> = emptyList(),
    val site: String = "",
    val learning: String = "",
    val succeeded: Boolean = false,
    val state: LifeStateData? = null,
)

class LifeActivityRunner(
    private val texts: LifeTexts,
    private val fetch: suspend (String) -> String = ::fetchFeed,
    private val sources: List<LifeFeedSource> = defaultLifeFeedSources(),
    private val random: Random = Random.Default,
) {
    private val cooldownUntil = LinkedHashMap<String, Long>()
    private var rotation = 0

    suspend fun run(
        choice: LifeChoice,
        interests: List<Interest> = emptyList(),
        state: LifeStateData = LifeStateData(),
    ): LifeActivityOutcome = withContext(Dispatchers.IO) {
        when (choice.action) {
            LifeAction.EXPLORE -> browse(choice, interests)
            LifeAction.RESEARCH -> learn(choice, interests)
            LifeAction.WRITING -> write(choice, state)
            LifeAction.REST -> LifeActivityOutcome(
                kind = EpisodeKind.NOTE,
                result = texts.text(R.string.life_activity_rest_result),
                headline = choice.reason,
            )
        }
    }

    private suspend fun browse(choice: LifeChoice, interests: List<Interest>): LifeActivityOutcome {
        val picked = pick(contentItems = 3, choice = choice, interests = interests)
        if (picked == null) return nothing(choice, interests)
        val items = picked.items
        return LifeActivityOutcome(
            kind = EpisodeKind.FORUM,
            result = texts.text(R.string.life_activity_browse_result, picked.source.label, items.size),
            headline = items.first().title,
            items = items,
            site = picked.source.label,
            learning = texts.text(R.string.life_activity_learning_seen, picked.source.label, items.size),
            succeeded = true,
        )
    }

    private suspend fun learn(choice: LifeChoice, interests: List<Interest>): LifeActivityOutcome {
        val picked = pick(contentItems = 2, choice = choice, interests = interests)
        if (picked == null) return nothing(choice, interests)
        val items = picked.items
        return LifeActivityOutcome(
            kind = EpisodeKind.READ,
            result = texts.text(R.string.life_activity_learn_result, picked.source.label, choice.topic),
            headline = items.first().title,
            items = items,
            site = picked.source.label,
            learning = texts.text(R.string.life_activity_learning_read, items.first().title),
            succeeded = true,
        )
    }

    private suspend fun pick(
        contentItems: Int,
        choice: LifeChoice,
        interests: List<Interest>,
    ): PickedFeed? {
        val now = System.currentTimeMillis()
        val candidates = pickLifeFeedSources(sources, interests, cooldownUntil, now, LIFECYCLE_FETCH_ATTEMPTS)
        if (candidates.isEmpty()) return null
        val rotated = candidates.drop(rotation % candidates.size) + candidates.take(rotation % candidates.size)
        var failure = ""
        for (source in rotated) {
            val raw = runCatching { fetch(source.url) }.getOrElse { error ->
                if (error is CancellationException) throw error
                failure = error.message.orEmpty()
                null
            }
            if (raw == null) continue
            val entries = runCatching { RssFeedParser.parse(raw) }.getOrDefault(emptyList())
            val matched = scoreEntries(entries, choice, interests).take(contentItems)
            if (matched.isEmpty()) {
                failure = texts.text(R.string.life_activity_no_entries)
                continue
            }
            rotation = (rotation + 1) % candidates.size
            markRead(source.id, now)
            return PickedFeed(
                source = source,
                items = matched.map { entry ->
                    LifeContentItem(
                        title = entry.title,
                        summary = entry.summary,
                        url = entry.link,
                        site = entry.site.ifBlank { source.label },
                    )
                },
            )
        }
        if (failure.isNotEmpty()) {
            cooldownUntil.putAll(rotated.associate { it.id to now + SOURCE_COOLDOWN_MS })
        }
        return null
    }

    private fun markRead(sourceId: String, now: Long) {
        cooldownUntil[sourceId] = now + SOURCE_COOLDOWN_MS
    }

    private fun scoreEntries(
        entries: List<FeedEntry>,
        choice: LifeChoice,
        interests: List<Interest>,
    ): List<FeedEntry> {
        val keywords = (listOf(choice.topic, choice.reason) + interests.map { it.topic })
            .filter { it.isNotBlank() && it.length >= 2 }
        fun score(entry: FeedEntry): Int {
            var hits = 0
            for (keyword in keywords) {
                val needle = keyword.trim().take(24)
                if (needle.isBlank()) continue
                if (entry.title.contains(needle) || entry.summary.contains(needle)) hits++
            }
            return hits
        }
        return entries.shuffled(random).sortedByDescending { score(it) }
    }

    private fun write(choice: LifeChoice, state: LifeStateData): LifeActivityOutcome {
        val mood = state.affect.moodHint()
        val body = texts.text(R.string.life_activity_writing_body, choice.topic, mood)
        val work = state.works.firstOrNull { choice.goalId != null && it.goalId == choice.goalId }
        val updated: LifeWork = if (work != null) {
            work.copy(content = work.content + "\n\n" + body, version = work.version + 1)
        } else {
            LifeWork(
                goalId = choice.goalId ?: choice.key,
                title = choice.topic.ifBlank { choice.reason },
                content = body,
            )
        }
        val next = state.copy(
            works = if (work != null) {
                state.works.map { previous -> if (previous.id == work.id) updated else previous }
            } else {
                state.works + updated
            },
        )
        return LifeActivityOutcome(
            kind = EpisodeKind.NOTE,
            result = texts.text(R.string.life_activity_writing_result),
            headline = choice.topic.ifBlank { choice.reason },
            learning = choice.topic,
            succeeded = true,
            state = next,
        )
    }

    private fun nothing(choice: LifeChoice, interests: List<Interest>): LifeActivityOutcome {
        val next = interests.maxByOrNull { it.strength * it.count }
        return LifeActivityOutcome(
            kind = EpisodeKind.NOTE,
            result = texts.text(
                R.string.life_activity_nothing_result,
                sources.firstOrNull()?.label.orEmpty(),
                sources.size,
            ),
            headline = choice.reason,
            learning = next?.topic.orEmpty(),
            succeeded = false,
        )
    }

    private data class PickedFeed(
    val source: LifeFeedSource,
    val items: List<FeedEntry>,
)

private const val SOURCE_COOLDOWN_MS = 6 * 3_600_000L
private const val LIFECYCLE_FETCH_ATTEMPTS = 3
