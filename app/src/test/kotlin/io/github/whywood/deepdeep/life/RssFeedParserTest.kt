package io.github.whywood.deepdeep.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssFeedParserTest {
    @Test
    fun parsesRssItemsWithSummaryAndSite() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
            <title>V2EX</title>
            <item>
            <title>第一条标题</title>
            <link>https://example.com/a</link>
            <description><![CDATA[<p>正文 <a href="x">链接</a></p>]]></description>
            </item>
            <item>
            <title>第二条标题</title>
            <link>https://example.com/b</link>
            <description>plain summary</description>
            </item>
            </channel></rss>
        """.trimIndent()

        val entries = RssFeedParser.parse(xml)

        assertEquals(2, entries.size)
        assertEquals("第一条标题", entries[0].title)
        assertEquals("https://example.com/a", entries[0].link)
        assertEquals("正文 链接", entries[0].summary)
        assertEquals("V2EX", entries[0].site)
        assertEquals("第二条标题", entries[1].title)
        assertEquals("plain summary", entries[1].summary)
    }

    @Test
    fun parsesAtomEntryLinkHref() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
            <title>Hacker News</title>
            <entry>
            <title>Show HN: 一个工具</title>
            <link rel="alternate" href="https://example.com/hn" />
            <summary>摘要 &amp; 说明</summary>
            </entry>
            </feed>
        """.trimIndent()

        val entries = RssFeedParser.parse(xml)

        assertEquals(1, entries.size)
        assertEquals("Show HN: 一个工具", entries[0].title)
        assertEquals("https://example.com/hn", entries[0].link)
        assertTrue(entries[0].summary.contains("摘要"), "summary=${entries[0].summary}")
        assertTrue(entries[0].summary.contains("&"), "summary=${entries[0].summary}")
        assertEquals("Hacker News", entries[0].site)
    }

    @Test
    fun malformedFeedReturnsEmpty() {
        assertTrue(RssFeedParser.parse("<rss><channel><item><title>").isEmpty())
        assertTrue(RssFeedParser.parse("").isEmpty())
        assertTrue(RssFeedParser.parse("not xml at all").isEmpty())
    }

    @Test
    fun summaryIsTruncatedAndHtmlStripped() {
        val longText = "x".repeat(900)
        val xml = """
            <rss><channel><title>S</title><item><title>t</title><description>$longText</description></item></channel></rss>
        """.trimIndent()

        val entries = RssFeedParser.parse(xml)

        assertEquals(1, entries.size)
        assertTrue(entries[0].summary.length <= 300)
        assertEquals(stripHtml("<b>加粗</b> 文字"), "加粗 文字")
    }
}
