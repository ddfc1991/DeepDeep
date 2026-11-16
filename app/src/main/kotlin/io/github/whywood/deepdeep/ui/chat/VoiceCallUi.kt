package io.github.whywood.deepdeep.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.whywood.deepdeep.R
import io.github.whywood.deepdeep.data.AppLanguage
import io.github.whywood.deepdeep.data.VoicePreferences
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.runtime.AssistantMessage
import io.github.whywood.deepdeep.ui.common.VOICE_MIN_VOLUME
import io.github.whywood.deepdeep.ui.common.voiceFailureText
import io.github.whywood.deepdeep.ui.common.VoicePickerMenu
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
/**
 * 全屏语音通话界面：状态指示（三态色/文案）+ 波形动效 + 正在说的那句字幕 + 挂断。
 */
@Composable
internal fun VoiceCallOverlay(
    messages: List<AssistantMessage>,
    voiceLanguage: AppLanguage,
    onSendPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { VoicePreferences(context) }
    val showCaptions by prefs.voiceCallShowCaptions.collectAsState(initial = true)
    val interruptThreshold by prefs.voiceCallInterrupt.collectAsState(initial = 0.6f)
    val voiceNameState = prefs.voiceName.collectAsState(initial = "")
    val controller = remember(context) {
        VoiceCallController(
            context = context,
            scope = scope,
            language = { voiceLanguage },
            sendPrompt = onSendPrompt,
            interruptThreshold = { interruptThreshold },
            initialVoice = { voiceNameState.value },
        )
    }
    val ui by controller.ui.collectAsState()
    var started by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var voicePickerOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            controller.start()
            started = true
        } else {
            permanentlyDenied = context.findActivity()?.let { activity ->
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO,
                )
            } == true
        }
    }

    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            controller.start()
            started = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(messages) {
        controller.onMessages(messages)
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    val colors = deepDeepColors()
    val phaseColor = when (ui.phase) {
        VoiceCallPhase.LISTENING -> colors.success
        VoiceCallPhase.THINKING -> colors.warning
        VoiceCallPhase.SPEAKING -> colors.accent
    }
    val statusText = when (ui.phase) {
        VoiceCallPhase.LISTENING -> stringResource(R.string.voice_call_status_listening)
        VoiceCallPhase.THINKING -> stringResource(R.string.voice_call_status_thinking)
        VoiceCallPhase.SPEAKING -> stringResource(R.string.voice_call_status_speaking)
    }
    val pulse by animateFloatAsState(
        targetValue = if (ui.phase == VoiceCallPhase.LISTENING) 1f else 0.55f,
        animationSpec = tween(durationMillis = 500),
        label = "call-pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .testTag("voice_call_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Xl, vertical = DdSpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 状态指示：呼吸圆点 + 状态文案（三态各自颜色）+ 换声音
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(phaseColor.copy(alpha = 0.18f * pulse), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(phaseColor, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = statusText,
                        style = DdType.heading,
                        color = phaseColor,
                    )
                    Spacer(Modifier.width(DdSpacing.Md))
                    // 换声音快捷按钮
                    DdPressable(
                        onClick = { voicePickerOpen = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(colors.wash, CircleShape)
                            .testTag("voice_call_change_voice"),
                        contentDescription = stringResource(R.string.voice_change_voice),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DdIcon(
                                icon = DdIcons.AudioLines,
                                tint = colors.inkSecondary,
                                size = 18.dp,
                            )
                        }
                    }
                }
                VoicePickerMenu(
                    expanded = voicePickerOpen,
                    onDismiss = { voicePickerOpen = false },
                    language = voiceLanguage,
                    selectedName = voiceNameState.value,
                    onSelect = { name ->
                        controller.setVoice(name)
                        scope.launch { prefs.setVoiceName(name) }
                    },
                )
            }
            Spacer(Modifier.height(DdSpacing.Lg))
            // 波形/呼吸动效
            VoiceWaveform(
                phase = ui.phase,
                volume = ui.volume,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("voice_call_waveform"),
            )
            Spacer(Modifier.height(DdSpacing.Lg))
            if (showCaptions) {
                if (ui.userText.isNotEmpty() || ui.partial.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.voice_call_you) + " " +
                            ui.userText.ifBlank { ui.partial },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = DdSpacing.Sm)
                            .testTag("voice_call_user"),
                        style = DdType.body,
                        color = colors.inkSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 分段字幕：已说过的灰显 + 正在说的那一句
                if (ui.spokenSentences.isNotEmpty() || ui.currentSentence.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        ui.spokenSentences.forEach { sentence ->
                            Text(
                                text = sentence,
                                modifier = Modifier.padding(bottom = DdSpacing.Xs),
                                style = DdType.bodySmall,
                                color = colors.inkTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (ui.currentSentence.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.voice_call_assistant) + " " + ui.currentSentence,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = DdSpacing.Sm)
                                    .testTag("voice_call_assistant"),
                                style = DdType.body,
                                color = colors.ink,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            ui.error?.let { error ->
                Text(
                    text = voiceFailureText(error),
                    style = DdType.bodySmall,
                    color = colors.danger,
                )
            }
            Spacer(Modifier.height(DdSpacing.Lg))
            if (ui.phase == VoiceCallPhase.SPEAKING) {
                DdPressable(
                    onClick = controller::bargeIn,
                    modifier = Modifier.testTag("voice_call_interrupt"),
                ) {
                    Text(
                        text = stringResource(R.string.voice_call_tap_to_interrupt),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                }
                Spacer(Modifier.height(DdSpacing.Md))
            }
            // 挂断
            DdPressable(
                onClick = onDismiss,
                modifier = Modifier
                    .size(64.dp)
                    .background(colors.danger, CircleShape)
                    .testTag("voice_call_hangup"),
                contentDescription = stringResource(R.string.voice_call_hang_up),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DdIcon(
                        icon = DdIcons.Phone,
                        tint = colors.onAccent,
                    )
                }
            }
            if (permanentlyDenied) {
                DdPressable(
                    onClick = { context.openCallAppSettings() },
                    modifier = Modifier.padding(top = DdSpacing.Sm),
                ) {
                    Text(
                        text = stringResource(R.string.voice_error_permission),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
            }
        }
    }
}

/** 波形/呼吸动效：聆听低频呼吸、思考脉冲、说话随正弦+音量起伏的波纹。 */
@Composable
private fun VoiceWaveform(
    phase: VoiceCallPhase,
    volume: Float,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    val periodMs = when (phase) {
        VoiceCallPhase.LISTENING -> 1800
        VoiceCallPhase.THINKING -> 900
        VoiceCallPhase.SPEAKING -> 480
    }
    val transition = rememberInfiniteTransition(label = "voice-wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "voice-wave-t",
    )
    val volumeNorm = ((volume - VOICE_MIN_VOLUME) / (1f - VOICE_MIN_VOLUME)).coerceIn(0f, 1f)
    val barCount = 9
    Canvas(modifier = modifier) {
        val barGap = size.width * 0.045f
        val barWidth = (size.width - barGap * (barCount - 1)) / barCount
        val baseFrac = when (phase) {
            VoiceCallPhase.LISTENING -> 0.30f
            VoiceCallPhase.THINKING -> 0.36f
            VoiceCallPhase.SPEAKING -> 0.28f
        }
        val amp = when (phase) {
            VoiceCallPhase.LISTENING -> 0.16f + 0.08f * volumeNorm
            VoiceCallPhase.THINKING -> 0.24f
            VoiceCallPhase.SPEAKING -> 0.20f + 0.30f * (0.5f + 0.5f * volumeNorm)
        }
        val color = when (phase) {
            VoiceCallPhase.LISTENING -> colors.success
            VoiceCallPhase.THINKING -> colors.warning
            VoiceCallPhase.SPEAKING -> colors.accent
        }
        val omega = when (phase) {
            VoiceCallPhase.LISTENING -> 1f
            VoiceCallPhase.THINKING -> 3f
            VoiceCallPhase.SPEAKING -> 4f
        }
        for (i in 0 until barCount) {
            val p = ((t * omega + i * 0.85f) * 2f).toDouble() * PI
            val h = (0.5 + 0.5 * sin(p)).toFloat()
            val frac = (baseFrac + amp * h).coerceIn(0.10f, 1f)
            val barHeight = size.height * frac
            val left = i * (barWidth + barGap)
            val top = (size.height - barHeight) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openCallAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
