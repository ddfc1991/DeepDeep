package io.github.whywood.deepdeep.life

import io.github.whywood.deepdeep.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlinx.coroutines.test.runTest

class LifeActivityRunnerTest {
    private val texts = LifeTexts { resId, args -> "$resId:" + args.joinToString(" | ") }

    private val choice = LifeChoice(
        action = LifeAction.EXPLORE,
        key = "explore",
        reason = "去逛论坛收集灵感",
        score = 1.0,
    )

    @Test
    fun exploreStoresRealFeedContent() = runTest {
        val runner = LifeActivityRunner(
            texts = texts,
            fetch = { feedXml("https://www.v2ex.com/index.xml") },
            sources = listOf(defaultLifeFeedSources().first()),
            random = Random(7),
        )

        val outcome = runner.run(choice)

        assertTrue(outcome.succeeded)
        assertEquals(EpisodeKind.FORUM, outcome.kind)
        assertEquals("V2EX", outcome.site)
        assertEquals(3, outcome.items.size)
        assertEquals("V2EX 第一条", outcome.items.first().title)
        assertTrue(outcome.items.first().url.startsWith("https://www.v2ex.com/"))
        assertTrue(outcome.learning.contains(R.string.life_activity_learning_seen.toString()))
    }

    @Test
    fun exploreReportsHonestFailureWhenFeedUnreachable() = runTest {
        val runner = LifeActivityRunner(
            texts = texts,
            fetch = { throw IllegalStateException("no network") },
            sources = defaultLifeFeedSources(),
            random = Random(7),
        )

        val outcome = runner.run(choice)

        assertFalse(outcome.succeeded)
        assertTrue(outcome.items.isEmpty())
        assertTrue(outcome.result.contains(R.string.life_activity_nothing_result.toString()))
    }

    @Test
    fun restDoesNothing() = runTest {
        val runner = LifeActivityRunner(
            texts = texts,
            fetch = { throw IllegalStateException("unused") },
            sources = defaultLifeFeedSources(),
            random = Random(7),
        )
        val restChoice = choice.copy(action = LifeAction.REST)

        val outcome = runner.run(restChoice)

        assertFalse(outcome.succeeded)
        assertEquals(EpisodeKind.NOTE, outcome.kind)
        assertTrue(outcome.items.isEmpty())
    }

    @Test
    fun writingAppendsToWork() = runTest {
        val runner = LifeActivityRunner(
            texts = texts,
            fetch = { throw IllegalStateException("unused") },
            sources = defaultLifeFeedSources(),
            random = Random(7),
        )
        val state = LifeStateData(works = listOf(LifeWork(goalId = "g1", title = "小作品", content = "旧内容")))
        val writing = LifeChoice(
            action = LifeAction.WRITING,
            key = "write",
            reason = "继续写",
            score = 1.0,
            goalId = "g1",
            topic = "我的问题",
        )

        val outcome = runner.run(writing, state = state)

        assertTrue(outcome.succeeded)
        val work = outcome.state?.works?.single()
        assertEquals(2, work?.version)
        assertTrue(work?.content?.startsWith("旧内容") == true)
        assertTrue(work?.content?.contains("我的问题") == true)
    }

    private fun feedXml(url: String): String = """
        <rss><channel><title>V2EX</title>
        <item><title>V2EX 第一条</title><link>$url/1</link><description>摘要一</description></item>
        <item><title>V2EX 第二条</title><link>$url/2</link><description>摘要二</description></item>
        <item><title>V2EX 第三条</title><link>$url/3</link><description>摘要三</description></item>
        </channel></rss>
    """.trimIndent()
}
