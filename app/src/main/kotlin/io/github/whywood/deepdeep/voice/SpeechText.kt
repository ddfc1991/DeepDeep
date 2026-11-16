package io.github.whywood.deepdeep.voice

private const val BREAK_CHARS = "。！？；.!?;\n，,、"

/** 播报上限：每次最多 [SPEAK_MAX_SENTENCES] 句，且总长不超过 [SPEAK_MAX_CHARS] 字。 */
internal const val SPEAK_MAX_SENTENCES = 2
internal const val SPEAK_MAX_CHARS = 80

/** 中文/英文句末标点切分（保留分隔符在句尾）；作为 VoiceCall 播报与字幕的单一事实源。 */
internal val SENTENCE_END = Regex("(?<=[。！？；!?;\\n])")

internal fun splitSpeechText(text: String, maxLength: Int): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return emptyList()
    if (maxLength <= 0 || source.length <= maxLength) return listOf(source)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < source.length) {
        val hardEnd = (start + maxLength).coerceAtMost(source.length)
        var end = if (hardEnd == source.length) hardEnd else preferredBreak(source, start, hardEnd)
        if (end <= start) {
            end = start + if (
                start + 1 < source.length &&
                source[start].isHighSurrogate() &&
                source[start + 1].isLowSurrogate()
            ) {
                2
            } else {
                1
            }
        }
        chunks += source.substring(start, end)
        start = end
    }
    return chunks
}

/** 截取最多 [maxSentences] 句且不超过 [maxChars] 字的前缀，用于短播报（超出的部分不朗读）。 */
internal fun clipForSpeech(
    text: String,
    maxSentences: Int = SPEAK_MAX_SENTENCES,
    maxChars: Int = SPEAK_MAX_CHARS,
): String {
    val source = text.trim()
    if (source.isEmpty()) return ""
    val sentences = source.split(SENTENCE_END).filter { it.isNotBlank() }
    val sb = StringBuilder()
    var count = 0
    var chars = 0
    for (sentence in sentences) {
        if (count >= maxSentences) break
        val budget = maxChars - chars
        val clipped = if (sentence.length <= budget) sentence else sentence.take(budget)
        if (clipped.isNotEmpty()) {
            sb.append(clipped)
            count++
            chars += clipped.length
        }
        if (chars >= maxChars || count >= maxSentences) break
    }
    return sb.toString().trim()
}

private fun preferredBreak(text: String, start: Int, hardEnd: Int): Int {
    val floor = start + (hardEnd - start) / 2
    for (index in hardEnd - 1 downTo floor) {
        if (text[index] in BREAK_CHARS) return boundaryBefore(text, index + 1)
    }
    for (index in hardEnd - 1 downTo floor) {
        if (text[index].isWhitespace()) return boundaryBefore(text, index + 1)
    }
    return boundaryBefore(text, hardEnd)
}

private fun boundaryBefore(text: String, index: Int): Int {
    if (index <= 0 || index >= text.length) return index
    return if (text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index - 1 else index
}
