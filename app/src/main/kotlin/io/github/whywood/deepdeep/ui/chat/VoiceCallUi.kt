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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
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
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.runtime.AssistantMessage
import io.github.whywood.deepdeep.ui.common.voiceFailureText

/**
 * 全屏语音通话界面：状态指示 + 实时字幕 + 挂断按钮。
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
    val controller = remember(context) {
        VoiceCallController(
            context = context,
            scope = scope,
            language = { voiceLanguage },
            sendPrompt = onSendPrompt,
            interruptThreshold = { interruptThreshold },
        )
    }
    val ui by controller.ui.collectAsState()
    var started by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

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
            // 状态指示灯 + 状态文本
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            when (ui.phase) {
                                VoiceCallPhase.LISTENING -> Color(0xFF3DDC84)
                                VoiceCallPhase.THINKING -> Color(0xFFFFC107)
                                VoiceCallPhase.SPEAKING -> Color(0xFF64B5F6)
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = DdType.heading,
                    color = colors.ink,
                )
            }
            Spacer(Modifier.height(DdSpacing.Lg))
            // 音量条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(colors.wash, CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ui.volume.coerceIn(0.06f, 1f) * pulse)
                        .height(6.dp)
                        .background(colors.accent, CircleShape),
                )
            }
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
                if (ui.assistantText.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.voice_call_assistant) + " " + ui.assistantText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = DdSpacing.Sm)
                            .testTag("voice_call_assistant"),
                        style = DdType.body,
                        color = colors.ink,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    .background(Color(0xFFE53935), CircleShape)
                    .testTag("voice_call_hangup"),
                contentDescription = stringResource(R.string.voice_call_hang_up),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DdIcon(
                        icon = DdIcons.Phone,
                        tint = Color.White,
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
