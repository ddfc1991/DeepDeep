package io.github.whywood.deepdeep.life

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 生活引擎 ——「女儿」的大脑接入层。
 *
 * 把 AstrBot 语义内核（AffectiveEngine 情感动力学 + LifePlanner 自主决策）
 * 接到 DeepDeep 的容器能力上：
 *  - 自主循环：每 tick 用 LifePlanner.choose 选下一件事 → 交给执行器跑（逛街/学习/签到）
 *  - 对话回合：onUserText 用 applyOwnerTurn 更新情感 → 返回大肥鱼人格的回复
 *  - 持久化：LifeStateData（人生档案）+ AffectiveState（情感）存盘，重启不忘
 *
 * 这个类不依赖任何 UI —— 是纯逻辑层，DeepDeep 的 AgentService/对话/定时器调用它。
 */
class LifeEngine(
    private val store: LifeStore,
    private val scope: CoroutineScope,
    /** 执行一件自主生活活动，返回真实执行结果（逛街/学习/写作都走这里） */
    private val runActivity: suspend (LifeChoice) -> LifeActivityOutcome,
) {
    private val planner = LifePlanner()
    private val cares = CareLedger()

    /** 人生档案（目标/作品/经历/兴趣/情绪/牵挂） */
    var state: LifeStateData = store.load()
        private set

    /** 大肥鱼情感（对话用，AffectiveEngine 管理） */
    var affect: AffectiveState = AffectiveState(scope = "owner", ownerId = "deepdeep")
        private set

    private var loopJob: Job? = null

    // ── 生命周期 ──────────────────────────────────────────────

    fun start(tickMinutes: Long = 30) {
        if (loopJob?.isActive == true) return
        LifeFeedBus.seed(state.episodes)
        loopJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(tickMinutes * 60_000L)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** 自主循环是否在运行（面板展示用）。 */
    val isRunning: Boolean
        get() = loopJob?.isActive == true

    // ── 自主循环 ──────────────────────────────────────────────

    /** 一次自主 tick：决定干什么 → 执行 → 记录 → 更新情感 → 存盘 */
    suspend fun tick(): LifeDecision {
        val now = System.currentTimeMillis()
        val decision = planner.choose(
            state = state,
            deviceBusy = false,
            lowEnergy = state.affect.isLowEnergy() || state.affect.isFrustrated(),
            now = now,
        )

        val chosen = decision.chosen
        if (chosen != null) {
            val outcome = runCatching { runActivity(chosen) }.getOrElse { error ->
                LifeActivityOutcome(
                    kind = EpisodeKind.NOTE,
                    result = "没做成：${error.message ?: "未知"}",
                    headline = chosen.reason,
                )
            }
            recordActivity(chosen, outcome, now)
            val event = if (outcome.succeeded) AffectEvent.SUCCESS else AffectEvent.FAIL
            state = state.copy(affect = state.affect.apply(event, now))
            state = recomputeInterests(chosen, now)
        } else {
            state = state.copy(affect = state.affect.apply(AffectEvent.REST, now))
        }

        store.save(state)
        return decision
    }

    /** 一次活动的落库：经历（含真实内容）+ 决策结果 + 动态流 */
    private fun recordActivity(
        chosen: LifeChoice,
        outcome: LifeActivityOutcome,
        now: Long,
    ) {
        val base = outcome.state ?: state
        val episode = LifeEpisode(
            kind = outcome.kind,
            title = outcome.headline,
            detail = outcome.result,
            goalId = chosen.goalId,
            occurredAt = now,
            source = outcome.site,
            items = outcome.items,
            learning = outcome.learning,
        )
        val recorded = chosen.copy(
            runStatus = if (outcome.succeeded) RunStatus.COMPLETED else RunStatus.FAILED,
            result = outcome.result,
            episodeId = episode.id,
        )
        state = base.copy(
            episodes = (base.episodes + episode).takeLast(EPISODE_LIMIT),
            history = (base.history + recorded).takeLast(200),
        )
        LifeFeedBus.emit(LifeFeedItem.fromEpisode(episode).copy(action = chosen.action))
    }

    /** 从最近经历里重算兴趣（自己做出来的偏好，不是预设） */
    private fun recomputeInterests(choice: LifeChoice, now: Long): LifeStateData {
        val topic = choice.topic.ifBlank { choice.key }
        if (topic.isBlank()) return state
        val existing = state.interests.firstOrNull { it.topic == topic }
        val updated = if (existing != null) {
            existing.copy(
                strength = (existing.strength + 0.2).coerceAtMost(1.2),
                count = existing.count + 1,
                lastAt = now,
            )
        } else {
            Interest(
                topic = topic,
                domain = choice.action.name.lowercase(),
                strength = 0.2,
                count = 1,
                lastAt = now,
            )
        }
        val others = state.interests.filterNot { it.topic == topic }
        return state.copy(interests = (others + updated).takeLast(40))
    }

    // ── 对话回合 ──────────────────────────────────────────────

    /**
     * 用户跟「女儿」说话：更新情感 + 返回大肥鱼人格的回复。
     * @return 回复文本（含当前心境线索，不报数字）
     */
    fun onUserText(text: String): String {
        val now = System.currentTimeMillis() / 1000.0
        val intent = classifyIntent(text)
        val distress = estimateDistress(text)

        val appraisal = OwnerAppraisal(
            intent = intent,
            userEmotion = if (distress > 0.5) "down" else "calm",
            userDistress = distress,
            delta = mapOf(
                "valence" to if (distress > 0.5) -0.2 else 0.08,
                "arousal" to if (distress > 0.5) -0.1 else 0.05,
                "warmth" to 0.5,
            ),
        )
        val (newAffect, summary) = applyOwnerTurn(affect, appraisal, now = now)
        affect = newAffect

        // 牵挂账本：用户随口说的事 → 记住；用户回话 → 结清
        val careItem = cares.extractFromUser(text)
        state = if (careItem != null) {
            state.copy(cares = cares.add(state.cares, careItem))
        } else {
            state.copy(cares = cares.settleByUserReply(state.cares, text))
        }
        store.save(state)

        return renderReply(summary, text)
    }

    /** 大肥鱼人格回复（不报数字，用语气泄露心境） */
    private fun renderReply(summary: OwnerTurnSummary, userText: String): String {
        val tag = summary.dominantTag
        val phrase = feltTonePhrase(affect.valence, affect.arousal, affect.pout)
        val careHint = if (affect.longing > 0.5) "（心里还惦记着你之前说的事）" else ""
        return when (tag) {
            "tsundere_softened" -> "嗯…你说$userText 我才没有特别在意，$careHint $phrase"
            "tsundere" -> "哼，$userText？我可不吃这套。$phrase"
            "distant" -> "哦，$userText 啊。$phrase"
            "melancholy" -> "……$userText。$phrase"
            "joyful" -> "好呀好呀，$userText 我在听！$phrase"
            "concerned" -> "你还好吗？$userText …$careHint $phrase"
            else -> "$userText 我听着呢。$phrase $careHint"
        }
    }

    /** 粗略意图分类（无 LLM 也能共情） */
    private fun classifyIntent(text: String): String {
        val t = text
        return when {
            t.contains("?") || t.contains("吗") || t.contains("什么") || t.contains("怎么") -> "question"
            t.contains("累") || t.contains("难过") || t.contains("压力") || t.contains("烦") -> "comfort"
            t.contains("爱你") || t.contains("想你") || t.contains("喜欢") -> "affection"
            t.contains("谢谢") || t.contains("好的") || t.contains("嗯") -> "acknowledge"
            else -> "casual"
        }
    }

    /** 粗略用户困扰度（0..1） */
    private fun estimateDistress(text: String): Double {
        val heavy = listOf("累", "难过", "不开心", "压力", "烦", "崩溃", "生病").count { text.contains(it) }
        return (heavy * 0.3).coerceAtMost(0.9)
    }

    // ── 生活事件注入 ──────────────────────────────────────────

    /** 外部事件（签到成功/挖到漏洞/发帖）→ 更新人生档案+情感 */
    fun onLifeEvent(kind: String, payload: Map<String, Any> = emptyMap()) {
        val (newAffect, _) = applyLifeEvent(affect, kind, payload)
        affect = newAffect
        val event = when (kind) {
            "reward" -> AffectEvent.BIG_SUCCESS
            "success" -> AffectEvent.SUCCESS
            "fail" -> AffectEvent.FAIL
            else -> null
        }
        if (event != null) {
            state = state.copy(affect = state.affect.apply(event))
        }
        store.save(state)
    }

    // ── 查询 ──────────────────────────────────────────────────

    /** 当前生活摘要（给对话/状态页） */
    fun summary(): String {
        val phase = planner.phase(System.currentTimeMillis(), state.rhythm)
        val mood = state.affect.moodHint()
        val activeGoals = state.goals.filter { it.status in setOf(GoalStatus.READY, GoalStatus.ACTIVE) }
        val goalText = activeGoals.firstOrNull()?.let { "在忙「${it.title}」" } ?: "暂无目标"
        return "现在是${phase.zh}，我$mood。$goalText。"
    }

    /** 建一个目标（用户交付或自主产生） */
    fun addGoal(title: String, curiosity: Boolean = false): LifeGoal {
        val goal = LifeGoal(title = title, status = GoalStatus.READY, curiosity = curiosity)
        state = if (curiosity) {
            state.copy(curiosityGoals = state.curiosityGoals + CuriosityGoal(title = title))
        } else {
            state.copy(goals = state.goals + goal)
        }
        store.save(state)
        return goal
    }
}

private const val EPISODE_LIMIT = 400
