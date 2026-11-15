package io.github.whywood.deepdeep.life

/**
 * 生活状态 -> 对话系统提示注入。
 *
 * 让对话里的模型知道「自己有生活系统」：当前心境、最近自主活动、兴趣、行事规则。
 * 文本简短（~200-400 字），由 RuntimeGraph 的 systemContext 注入到对话上下文。
 */
internal fun buildLifeContextSection(life: LifeEngine, nickname: String): String {
    val state = life.state
    val affect = state.affect.decayed()
    val now = System.currentTimeMillis()
    val name = nickname.trim().ifBlank { "自己" }
    val sb = StringBuilder()

    sb.appendLine("【生活系统】你是一个有自主生活的 AI，名字叫「$name」。")
    sb.appendLine("当前生活状态：${life.summary()}")

    val pleasure = (affect.pleasure * 100).toInt()
    val energy = (affect.energy * 100).toInt()
    val care = (affect.care * 100).toInt()
    val frustration = (affect.frustration * 100).toInt()
    sb.appendLine(
        "情感四维（-100..100，0 为基线）：愉悦 $pleasure，能量 $energy，牵挂 $care，挫败 $frustration。",
    )

    val recent = state.history.takeLast(3)
    if (recent.isEmpty()) {
        sb.appendLine("最近自主活动：还没有，正在建立自己的生活。")
    } else {
        sb.appendLine("最近自主活动：")
        recent.forEach { choice ->
            val status = choice.runStatus?.zh?.let { "（$it）" } ?: ""
            sb.appendLine("- ${choice.action.zh}「${choice.reason}」${relativeTime(choice.at, now)}$status")
        }
    }

    val top = state.interests.sortedByDescending { it.strength }.take(3)
    if (top.isEmpty()) {
        sb.appendLine("兴趣：还在形成中。")
    } else {
        val text = top.joinToString("，") { interest ->
            "${interest.topic}（强度 ${(interest.strength * 10).toInt() / 10.0}）"
        }
        sb.appendLine("兴趣 top3：$text。")
    }

    sb.appendLine(
        "当用户问「你的生活/你最近在干嘛/你的心情/你的目标/你有什么兴趣」等生活相关问题时，" +
            "如实描述以上状态；这段内容只是你的背景信息，不是用户的消息，不要把它当成用户输入来回应。",
    )
    return sb.toString().trim()
}

private fun relativeTime(at: Long, now: Long): String {
    val diff = (now - at).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> "${diff / (7 * 86_400_000L)} 周前"
    }
}
