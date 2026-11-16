package io.github.whywood.deepdeep.life

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LifeFeedItem(
    val key: String,
    val action: LifeAction,
    val site: String,
    val headline: String,
    val items: List<LifeContentItem> = emptyList(),
    val learning: String = "",
    val at: Long,
    val succeeded: Boolean = true,
) {
    companion object {
        fun fromEpisode(episode: LifeEpisode): LifeFeedItem = LifeFeedItem(
            key = episode.id,
            action = when (episode.kind) {
                EpisodeKind.FORUM -> LifeAction.EXPLORE
                EpisodeKind.READ -> LifeAction.RESEARCH
                EpisodeKind.VIDEO, EpisodeKind.PHONE -> LifeAction.RESEARCH
                EpisodeKind.NOTE -> if (episode.items.isNotEmpty()) LifeAction.WRITING else LifeAction.REST
            },
            site = episode.source,
            headline = episode.title,
            items = episode.items,
            learning = episode.learning,
            at = episode.occurredAt,
            succeeded = episode.items.isNotEmpty() || episode.detail.isNotBlank(),
        )
    }
}

object LifeFeedBus {
    private const val FEED_LIMIT = 30
    private val mutableItems = MutableStateFlow<List<LifeFeedItem>>(emptyList())
    val items: StateFlow<List<LifeFeedItem>> = mutableItems.asStateFlow()

    @Synchronized
    fun emit(item: LifeFeedItem) {
        mutableItems.value = (listOf(item) + mutableItems.value.filterNot { it.key == item.key })
            .take(FEED_LIMIT)
    }

    @Synchronized
    fun seed(episodes: List<LifeEpisode>) {
        val seeded = episodes.takeLast(FEED_LIMIT).map(LifeFeedItem::fromEpisode).asReversed()
        mutableItems.value = seeded
    }
}
