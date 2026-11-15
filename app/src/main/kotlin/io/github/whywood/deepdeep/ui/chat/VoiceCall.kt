package io.github.whywood.deepdeep.ui.chat

import android.content.Context
import io.github.whywood.deepdeep.data.AppLanguage
import io.github.whywood.deepdeep.runtime.AssistantMessage
import io.github.whywood.deepdeep.runtime.AssistantRole
import io.github.whywood.deepdeep.runtime.AssistantStep
import io.github.whywood.deepdeep.runtime.MessageStatus
import io.github.whywood.deepdeep.ui.common.VOICE_MIN_VOLUME
import io.github.whywood.deepdeep.voice.ContinuousVoiceSession
import io.github.whywood.deepdeep.voice.SpeechError
import io.github.whywood.deepdeep.voice.SpeechEvent
import io.github.whywood.deepdeep.voice.TextSpeaker
import io.github.whywood.deepdeep.voice.VoiceLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class VoiceCallPhase { LISTENING, THINKING, SPEAKING }

internal data class VoiceCallUi(
    val phase: VoiceCallPhase = VoiceCallPhase.LISTENING,
    val userText: String = "",
    val assistantText: String = "",
    val partial: String = "",
    val volume: Float = VOICE_MIN_VOLUME,
    val error: SpeechError? = null,
)

/**
 * 全双工语音通话状态机：LISTENING（聆听）→ THINKING（等待模型）→ SPEAKING（逐句播报）。
 *
 * 麦克风全程开启：LISTENING 时识别用户语句并发送；SPEAKING 时用音量阈值检测打断
 * （barge-in），高于阈值立即停止播报回到聆听。回复按句切分送 TTS，首句尽快出声。
 */
internal class VoiceCallController(
    context: Context,
    private val scope: CoroutineScope,
    private val language: () -> AppLanguage,
    private val sendPrompt: (String) -> Unit,
    private val interruptThreshold: () -> Float,
) {
    private val session = ContinuousVoiceSession(context)
    private val speaker = TextSpeaker(context)
    private val _ui = MutableStateFlow(VoiceCallUi())
    val ui: StateFlow<VoiceCallUi> = _ui.asStateFlow()

    private var listenJob: Job? = null
    private var speakPump: Job? = null
    private val pendingSentences = ArrayDeque<String>()
    private var spokenChars = 0
    private var streamDone = false
    private var lastStreamId: Long? = null

    fun start() {
        speaker.stop()
        pendingSentences.clear()
        spokenChars = 0
        streamDone = false
        lastStreamId = null
        _ui.update {
            it.copy(
                phase = VoiceCallPhase.LISTENING,
                userText = "",
                assistantText = "",
                partial = "",
                volume = VOICE_MIN_VOLUME,
                error = null,
            )
        }
        startListening()
    }

    fun close() {
        listenJob?.cancel()
        speakPump?.cancel()
        session.cancel()
        speaker.stop()
        speaker.release()
    }

    fun bargeIn() {
        if (_ui.value.phase != VoiceCallPhase.SPEAKING) return
        speaker.stop()
        pendingSentences.clear()
        spokenChars = 0
        streamDone = false
        // 重启聆听，丢弃被 TTS 污染的语音段
        startListening()
    }

    /** 由 Compose 在消息变化时驱动；处理 THINKING→SPEAKING 与逐句播报。 */
    fun onMessages(messages: List<AssistantMessage>) {
        val phase = _ui.value.phase
        if (phase != VoiceCallPhase.THINKING && phase != VoiceCallPhase.SPEAKING) return
        val streaming = messages.lastOrNull { it.role == AssistantRole.ASSISTANT && it.streaming }
        if (streaming != null) {
            if (streaming.id != lastStreamId) {
                // 新的一条流式回复：重置播报游标
                lastStreamId = streaming.id
                spokenChars = 0
                streamDone = false
                pendingSentences.clear()
            }
            if (_ui.value.phase == VoiceCallPhase.THINKING) {
                _ui.update { it.copy(phase = VoiceCallPhase.SPEAKING) }
            }
            _ui.update { it.copy(assistantText = speakable(streaming)) }
            speakNewText(speakable(streaming))
            pumpSpeech()
        } else {
            finishStream(messages)
        }
    }

    private fun startListening() {
        listenJob?.cancel()
        spokenChars = 0
        streamDone = false
        _ui.update { it.copy(phase = VoiceCallPhase.LISTENING, partial = "") }
        val tag = VoiceLanguage.recognitionTag(language())
        listenJob = scope.launch {
            session.start(tag).collect { event ->
                when (event) {
                    is SpeechEvent.Partial -> {
                        if (_ui.value.phase == VoiceCallPhase.LISTENING) {
                            _ui.update { it.copy(partial = event.text) }
                        }
                    }
                    is SpeechEvent.Volume -> handleVolume(event.level)
                    is SpeechEvent.Final -> handleFinal(event.text)
                    is SpeechEvent.Failed -> _ui.update { it.copy(error = event.error) }
                    SpeechEvent.Timeout -> _ui.update { it.copy(error = SpeechError.SPEECH_TIMEOUT) }
                }
            }
        }
    }

    private fun handleVolume(level: Float) {
        _ui.update { it.copy(volume = level) }
        if (_ui.value.phase == VoiceCallPhase.SPEAKING && level >= interruptThreshold()) {
            bargeIn()
        }
    }

    private fun handleFinal(text: String) {
        if (_ui.value.phase != VoiceCallPhase.LISTENING) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _ui.update { it.copy(partial = "") }
            return
        }
        val joined = buildString {
            val existing = _ui.value.userText.trim()
            if (existing.isNotEmpty()) {
                append(existing)
                append('\n')
            }
            append(trimmed)
        }
        _ui.update { it.copy(userText = joined, partial = "") }
        sendPrompt(trimmed)
        _ui.update { it.copy(phase = VoiceCallPhase.THINKING) }
    }

    private fun finishStream(messages: List<AssistantMessage>) {
        if (_ui.value.phase != VoiceCallPhase.SPEAKING) return
        val last = messages.lastOrNull { it.role == AssistantRole.ASSISTANT && it.status == MessageStatus.DONE }
        val full = last?.let(::speakable).orEmpty()
        if (full.isNotEmpty()) {
            _ui.update { it.copy(assistantText = full) }
            if (full.length > spokenChars) {
                val remainder = full.substring(spokenChars).trim()
                if (remainder.isNotEmpty()) {
                    pendingSentences.addLast(remainder)
                    spokenChars = full.length
                }
            }
        }
        streamDone = true
        pumpSpeech()
    }

    private fun speakNewText(text: String) {
        if (text.length <= spokenChars) return
        val newChunk = text.substring(spokenChars)
        val parts = newChunk.split(SENTENCE_END)
        val trailing = parts.lastOrNull().orEmpty()
        spokenChars = text.length - trailing.length
        parts.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }.forEach { sentence ->
            pendingSentences.addLast(sentence)
        }
    }

    private fun pumpSpeech() {
        if (speakPump?.isActive == true) return
        speakPump = scope.launch {
            while (true) {
                if (!speaker.speaking && pendingSentences.isNotEmpty()) {
                    val sentence = pendingSentences.removeFirst()
                    speaker.speak(sentence, language())
                }
                when (_ui.value.phase) {
                    VoiceCallPhase.SPEAKING -> {
                        if (streamDone && pendingSentences.isEmpty() && !speaker.speaking) {
                            startListening()
                            break
                        }
                    }

                    else -> break
                }
                delay(40)
            }
        }
    }

    private fun speakable(message: AssistantMessage): String {
        val trailing = message.steps.lastOrNull() as? AssistantStep.Text
        return message.text.trim().ifBlank { trailing?.text?.trim().orEmpty() }
    }

    private companion object {
        // 按中文/英文句末标点切分（保留分隔符在句尾）
        val SENTENCE_END = Regex("(?<=[。！？；!?;\\n])")
    }
}
