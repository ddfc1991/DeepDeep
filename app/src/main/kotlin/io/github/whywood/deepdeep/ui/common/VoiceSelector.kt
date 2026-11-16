package io.github.whywood.deepdeep.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.R
import io.github.whywood.deepdeep.data.AppLanguage
import io.github.whywood.deepdeep.design.DdMenu
import io.github.whywood.deepdeep.design.DdMenuItem
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.voice.TtsVoice
import io.github.whywood.deepdeep.voice.listSystemTtsVoices

/**
 * 系统 TTS 音色选择器：枚举系统 voices（按当前语言过滤），提供「系统默认」与每个音色。
 * 选择后由调用方持久化并应用。
 */
@Composable
internal fun VoicePickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    language: AppLanguage,
    selectedName: String,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = deepDeepColors()
    var voices by remember { mutableStateOf<List<TtsVoice>?>(null) }
    LaunchedEffect(expanded, language) {
        if (expanded) {
            voices = listSystemTtsVoices(context, language)
        }
    }
    DdMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        menuWidth = 320.dp,
    ) {
        DdMenuItem(
            text = stringResource(R.string.voice_system_default),
            selected = selectedName.isBlank(),
            onClick = {
                onSelect("")
                onDismiss()
            },
            testTag = "voice_pick_default",
        )
        val list = voices
        when {
            list == null -> {
                DdMenuItem(
                    text = stringResource(R.string.voice_list_loading),
                    onClick = {},
                )
            }

            list.isEmpty() -> {
                DdMenuItem(
                    text = stringResource(R.string.voice_list_empty),
                    onClick = {},
                )
            }

            else -> {
                list.forEach { voice ->
                    DdMenuItem(
                        text = voice.name,
                        selected = voice.name == selectedName,
                        onClick = {
                            onSelect(voice.name)
                            onDismiss()
                        },
                        trailing = {
                            Text(
                                text = voice.locale,
                                style = DdType.bodySmall,
                                color = colors.inkTertiary,
                                modifier = Modifier.testTag("voice_pick_${voice.name}"),
                            )
                        },
                    )
                }
            }
        }
    }
}
