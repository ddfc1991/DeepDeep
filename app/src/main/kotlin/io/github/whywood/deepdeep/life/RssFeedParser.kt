package io.github.whywood.deepdeep.life

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FeedEntry(
    val title: String,
    val link: String,
    val summary: String,
    val site: String,
)

object RssFeedParser {
    fun parse(xml: String): List<FeedEntry> {
        if (xml.isBlank()) return emptyList()
        return runCatching {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val handler = FeedHandler()
            factory.newSAXParser().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), handler)
            handler.entries
        }.getOrDefault(emptyList())
    }
}

class FeedHandler : DefaultHandler() {
    private val parsed = mutableListOf<FeedEntry>()
    private var site = ""
    private var entryTitle = ""
    private var entryLink = ""
    private var entrySummary = ""
    private var field: String? = null
    private var inTop = false
    private var inEntry = false

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        val name = localName.ifEmpty { qName.substringAfterLast(':') }
        when {
            name == "item" || name == "entry" -> {
                inEntry = true
                entryTitle = ""
                entryLink = ""
                entrySummary = ""
            }

            inEntry && name == "link" && entryLink.isEmpty() -> {
                attributes.getValue("href")?.takeIf { it.isNotBlank() }?.let { entryLink = it }
                field = "link"
            }

            inEntry && name == "title" && entryTitle.isEmpty() -> field = "title"

            inEntry && (name == "description" || name == "summary" || name == "content" ||
                name == "encoded") && entrySummary.isEmpty() -> field = "summary"

            !inEntry && (name == "channel" || name == "feed") -> inTop = true

            inTop && !inEntry && name == "title" && site.isEmpty() -> field = "site"

            else -> field = null
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        val text = String(ch, start, length).trim()
        if (text.isEmpty() || field == null) return
        when (field) {
            "title" -> entryTitle += text
            "link" -> entryLink += text
            "summary" -> entrySummary += text
            "site" -> site += text
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val name = localName.ifEmpty { qName.substringAfterLast(':') }
        when {
            name == "item" || name == "entry" -> {
                if (entryTitle.isNotBlank()) {
                    parsed += FeedEntry(
                        title = entryTitle.trim(),
                        link = entryLink.trim(),
                        summary = stripHtml(entrySummary.trim()).take(SUMMARY_LIMIT),
                        site = site,
                    )
                }
                inEntry = false
                field = null
            }

            name == "channel" || name == "feed" -> {
                inTop = false
                field = null
            }

            else -> field = null
        }
    }

    val entries: List<FeedEntry>
        get() = parsed
}

private const val SUMMARY_LIMIT = 300

private val htmlTag = Regex("<[^>]+>")

internal fun stripHtml(raw: String): String {
    val decoded = htmlTag.replace(raw, " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
    return decoded.replace(Regex("\\s+"), " ").trim()
}

suspend fun fetchFeed(url: String): String = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; DeepDeep/1.0; life-feed)")
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        if (status !in 200..299 || stream == null) throw IOException("feed unavailable (HTTP $status)")
        stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText().take(FETCH_TEXT_LIMIT) }
    } finally {
        connection?.disconnect()
    }
}

private const val FETCH_TIMEOUT_MS = 15_000
private const val FETCH_TEXT_LIMIT = 400_000
