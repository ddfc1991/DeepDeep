package io.github.whywood.deepdeep.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.R
import io.github.whywood.deepdeep.design.DeepDeepColors
import io.github.whywood.deepdeep.design.DdEmptyState
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPageScaffold
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdProgressBar
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.life.AffectState
import io.github.whywood.deepdeep.life.GoalStatus
import io.github.whywood.deepdeep.life.Interest
import io.github.whywood.deepdeep.life.LifeChoice
import io.github.whywood.deepdeep.life.LifeStateData
import io.github.whywood.deepdeep.life.RunStatus
import io.github.whywood.deepdeep.ui.common.rememberRelativeTime
import java.util.Locale

@Composable
internal fun LifeScreen(
    summary: String?,
    state: LifeStateData?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isRunning: Boolean,
    onAddGoal: (String) -> Unit,
    onComfort: () -> String,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.life_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            Text(
                text = stringResource(R.string.life_title),
                modifier = Modifier.weight(1f),
                style = DdType.heading,
                color = colors.ink,
            )
            DdIconButton(
                icon = DdIcons.Refresh,
                onClick = onRefresh,
                contentDescription = stringResource(R.string.life_refresh),
                tint = colors.inkSecondary,
                iconSize = 19.dp,
            )
        },
        modifier = modifier.testTag("life_page"),
    ) {
        if (state == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DdLoadingBlock()
            }
        } else {
            LifeContent(
                summary = summary,
                state = state,
                isRunning = isRunning,
                onAddGoal = onAddGoal,
                onComfort = onComfort,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LifeContent(
    summary: String?,
    state: LifeStateData,
    isRunning: Boolean,
    onAddGoal: (String) -> Unit,
    onComfort: () -> String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.Xxl),
    ) {
        Spacer(Modifier.height(DdSpacing.Sm))
        // 1. 当前状态：现在在做什么 + 上次活动 + 循环状态
        LifeStatusCard(summary, state, isRunning)
        Spacer(Modifier.height(DdSpacing.Xxl))
        DdSectionLabel(stringResource(R.string.life_interaction))
        Spacer(Modifier.height(DdSpacing.Sm))
        // 2. 快捷互动：安排目标 + 关心它
        InteractionCard(onAddGoal, onComfort)
        Spacer(Modifier.height(DdSpacing.Xxl))
        DdSectionLabel(stringResource(R.string.life_affect))
        Spacer(Modifier.height(DdSpacing.Sm))
        AffectCard(state.affect)
        Spacer(Modifier.height(DdSpacing.Xxl))
        DdSectionLabel(stringResource(R.string.life_interest))
        Spacer(Modifier.height(DdSpacing.Sm))
        InterestsCard(state.interests)
        Spacer(Modifier.height(DdSpacing.Xxl))
        DdSectionLabel(stringResource(R.string.life_history))
        Spacer(Modifier.height(DdSpacing.Sm))
        // 3. 活动真实结果：可展开看 reason + 决策因素 + 结果状态
        HistoryTimeline(state.history.takeLast(8))
        Spacer(Modifier.height(DdSpacing.Xxl))
        DdSectionLabel(stringResource(R.string.life_goals))
        Spacer(Modifier.height(DdSpacing.Sm))
        GoalsCard(state)
        Spacer(Modifier.height(DdSpacing.Xxl))
        DdSectionLabel(stringResource(R.string.life_coin))
        Spacer(Modifier.height(DdSpacing.Sm))
        AssetsCard(state.coin, state.levelXp)
        Spacer(Modifier.height(DdSpacing.Xxl))
    }
}

@Composable
private fun LifeStatusCard(summary: String?, state: LifeStateData, isRunning: Boolean) {
    val colors = deepDeepColors()
    val last = state.history.lastOrNull()
    DdGroupCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdIcon(icon = DdIcons.Sparkles, tint = colors.accent, size = 20.dp)
                Spacer(Modifier.width(DdSpacing.Md))
                Text(
                    text = stringResource(R.string.life_status),
                    style = DdType.body,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (isRunning) {
                        stringResource(R.string.life_status_running)
                    } else {
                        stringResource(R.string.life_status_stopped)
                    },
                    style = DdType.micro,
                    color = if (isRunning) colors.accent else colors.inkTertiary,
                )
            }
            Spacer(Modifier.height(DdSpacing.Sm))
            Text(
                text = summary ?: stringResource(R.string.life_empty),
                style = DdType.body,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xs))
            Text(
                text = if (last != null) {
                    stringResource(
                        R.string.life_status_last,
                        "${last.action.zh}「${last.reason}」",
                        rememberRelativeTime(last.at),
                    )
                } else {
                    stringResource(R.string.life_status_none)
                },
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
        }
    }
}

@Composable
private fun InteractionCard(
    onAddGoal: (String) -> Unit,
    onComfort: () -> String,
) {
    val colors = deepDeepColors()
    var goalInput by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<String?>(null) }

    fun submitGoal() {
        val title = goalInput.trim()
        if (title.isEmpty()) return
        onAddGoal(title)
        goalInput = ""
        reply = null
    }

    DdGroupCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
        ) {
            DdTextField(
                value = goalInput,
                onValueChange = { goalInput = it },
                placeholder = stringResource(R.string.life_goal_hint),
                onDone = { submitGoal() },
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            Row(Modifier.fillMaxWidth()) {
                DdPrimaryButton(
                    text = stringResource(R.string.life_goal_add),
                    onClick = { submitGoal() },
                    modifier = Modifier.weight(1f),
                    enabled = goalInput.isNotBlank(),
                )
                Spacer(Modifier.width(DdSpacing.Sm))
                DdPrimaryButton(
                    text = stringResource(R.string.life_comfort),
                    onClick = { reply = onComfort() },
                    modifier = Modifier.weight(1f),
                )
            }
            reply?.let { text ->
                Spacer(Modifier.height(DdSpacing.Sm))
                Text(
                    text = stringResource(R.string.life_comfort_reply, text),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun AffectCard(affect: AffectState) {
    val colors = deepDeepColors()
    val current = affect.decayed()
    DdGroupCard {
        Text(
            text = stringResource(R.string.life_mood_hint, current.moodHint()),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
        )
        AffectBar(stringResource(R.string.life_affect_pleasure), current.pleasure, (current.pleasure + 1.0) / 2.0)
        AffectBar(stringResource(R.string.life_affect_energy), current.energy, current.energy)
        AffectBar(stringResource(R.string.life_affect_care), current.care, current.care)
        AffectBar(stringResource(R.string.life_affect_frustration), current.frustration, current.frustration)
    }
}

@Composable
private fun AffectBar(label: String, value: Double, progress: Double) {
    val colors = deepDeepColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = DdType.body, color = colors.ink)
            Spacer(Modifier.weight(1f))
            Text(
                text = String.format(Locale.US, "%.2f", value),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
        }
        Spacer(Modifier.height(DdSpacing.Xs))
        DdProgressBar(progress = progress.toFloat().coerceIn(0f, 1f))
    }
}

@Composable
private fun InterestsCard(interests: List<Interest>) {
    val colors = deepDeepColors()
    if (interests.isEmpty()) {
        DdGroupCard {
            DdEmptyState(stringResource(R.string.life_interest_empty))
        }
        return
    }
    val sorted = interests.sortedByDescending { it.strength }
    DdGroupCard {
        sorted.forEach { interest ->
            DdListRow(
                title = interest.topic,
                subtitle = stringResource(
                    R.string.life_interest_subtitle,
                    interest.domain,
                    interest.count,
                    rememberRelativeTime(interest.lastAt),
                ),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = String.format(Locale.US, "%.1f", interest.strength),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                        )
                        Spacer(Modifier.width(DdSpacing.Sm))
                        StrengthBar(interest.strength)
                    }
                },
            )
        }
    }
}

@Composable
private fun StrengthBar(strength: Double) {
    val colors = deepDeepColors()
    Box(
        Modifier
            .width(44.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(colors.wash),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(strength.coerceIn(0.0, 1.0).toFloat())
                .background(colors.accent),
        )
    }
}

@Composable
private fun HistoryTimeline(history: List<LifeChoice>) {
    if (history.isEmpty()) {
        DdGroupCard {
            DdEmptyState(stringResource(R.string.life_history_empty))
        }
        return
    }
    val colors = deepDeepColors()
    var expandedId by remember { mutableStateOf<String?>(null) }
    Column {
        history.forEachIndexed { index, choice ->
            val last = index == history.lastIndex
            val expanded = expandedId == choice.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { expandedId = if (expanded) null else choice.id },
            ) {
                Row(
                    Modifier
                        .height(IntrinsicSize.Min)
                        .fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .width(18.dp)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                        if (!last) {
                            Box(
                                Modifier
                                    .width(1.5.dp)
                                    .weight(1f)
                                    .background(colors.hairline),
                            )
                        }
                    }
                    Spacer(Modifier.width(DdSpacing.Sm))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(bottom = if (last) 0.dp else DdSpacing.Lg),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = choice.action.zh,
                                style = DdType.body,
                                color = colors.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            choice.runStatus?.let { status ->
                                Spacer(Modifier.width(DdSpacing.Sm))
                                Text(
                                    text = status.zh,
                                    style = DdType.micro,
                                    color = runStatusColor(status, colors),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = rememberRelativeTime(choice.at),
                                style = DdType.bodySmall,
                                color = colors.inkTertiary,
                            )
                        }
                        Spacer(Modifier.height(DdSpacing.Xs))
                        Text(
                            text = choice.reason,
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                            maxLines = if (expanded) 6 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (expanded) {
                            Spacer(Modifier.height(DdSpacing.Sm))
                            ExpandedChoiceDetail(choice)
                        }
                    }
                }
            }
        }
    }
}

/** 展开后的活动详情：原因 + 决策因素 + 得分 + 结果状态（失败标明原因）。 */
@Composable
private fun ExpandedChoiceDetail(choice: LifeChoice) {
    val colors = deepDeepColors()
    Column {
        if (choice.factors.isNotEmpty()) {
            Text(
                text = stringResource(R.string.life_history_factors),
                style = DdType.bodySmall,
                color = colors.ink,
            )
            choice.factors.forEach { factor ->
                Text(
                    text = stringResource(R.string.life_history_factor, factor.reason, "%.2f".format(factor.points)),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    modifier = Modifier.padding(start = DdSpacing.Xs),
                )
            }
            Spacer(Modifier.height(DdSpacing.Xs))
        }
        Text(
            text = stringResource(R.string.life_history_score, "%.2f".format(choice.score)),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
        if (choice.topic.isNotBlank()) {
            Text(
                text = stringResource(R.string.life_history_topic, choice.topic),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
        }
        choice.runStatus?.let { status ->
            Text(
                text = stringResource(R.string.life_history_status, status.zh),
                style = DdType.bodySmall,
                color = runStatusColor(status, colors),
            )
        }
    }
}

@Composable
private fun GoalsCard(state: LifeStateData) {
    val activeGoals = state.goals.filter {
        it.status == GoalStatus.READY || it.status == GoalStatus.ACTIVE
    }
    if (activeGoals.isEmpty() && state.curiosityGoals.isEmpty()) {
        DdGroupCard {
            DdEmptyState(stringResource(R.string.life_goals_empty))
        }
        return
    }
    DdGroupCard {
        activeGoals.forEach { goal ->
            DdListRow(
                title = goal.title,
                subtitle = goal.status.zh,
                icon = DdIcons.Target,
            )
        }
        state.curiosityGoals.forEach { goal ->
            DdListRow(
                title = goal.title,
                subtitle = stringResource(R.string.life_goal_curiosity),
                icon = DdIcons.Sparkles,
            )
        }
    }
}

@Composable
private fun AssetsCard(coin: Int, levelXp: Map<String, Int>) {
    DdGroupCard {
        DdListRow(
            title = stringResource(R.string.life_coin),
            value = "$coin",
        )
        levelXp.toSortedMap().forEach { (key, value) ->
            DdListRow(
                title = stringResource(R.string.life_level, key),
                value = "$value",
            )
        }
    }
}

private fun runStatusColor(status: RunStatus, colors: DeepDeepColors): Color = when (status) {
    RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.INTERRUPTED,
    RunStatus.CANCELLED, RunStatus.BUDGET_EXHAUSTED,
    -> colors.danger
    RunStatus.RUNNING -> colors.accent
    RunStatus.COMPLETED, RunStatus.NOT_STARTED -> colors.inkTertiary
}
