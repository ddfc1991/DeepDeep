package io.github.whywood.deepdeep.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.channels.SendChannel

private const val LOCAL_SAMPLE_RATE = 16_000
private const val LOCAL_FRAME_MS = 100
private const val LOCAL_FRAME_SAMPLES = LOCAL_SAMPLE_RATE / (1_000 / LOCAL_FRAME_MS)

/**
 * 本地离线语音识别引擎（sherpa-onnx + paraformer-zh-small）。
 *
 * 在系统 SpeechRecognizer 不可用（Android 17 ROM 上识别服务对第三方不可见）时作为主路径；
 * 模型未就绪/初始化失败时由调用方回退到系统路径。
 *
 * - 识别：OfflineRecognizer（AssetManager 直接加载 assets 下的 model.int8.onnx + tokens.txt）
 * - 录音：AudioRecord 16kHz mono PCM16，逐帧计算 RMS -> Volume 事件
 * - VAD：能量阈值 + 静音 800ms 判句尾 + 单段最长 30s；每 ~1.5s 用尾窗 5s 的 PCM 跑一次识别发 Partial
 * - 句尾：createStream + acceptWaveform + decode -> Final(text)；空文本 -> Failed(NO_MATCH)
 * - 解码统一跑在单线程 executor 上（串行，避免 sherpa 并发解码）
 */
internal class LocalSpeechEngine private constructor(
    private val recognizer: OfflineRecognizer,
) {
    private val decoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sherpa-decode").apply { isDaemon = true }
    }

    /** 是否支持该语言标签（paraformer-zh 只处理中文）。 */
    fun supports(languageTag: String): Boolean {
        val tag = languageTag.lowercase()
        return tag.startsWith("zh") || tag.startsWith("cmn")
    }

    fun createSession(continuous: Boolean, events: SendChannel<SpeechEvent>): LocalSpeechSession =
        LocalSpeechSession(this, continuous, events)

    /** 在解码线程上执行一次完整识别。返回空串表示没有识别到文本。 */
    internal fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, LOCAL_SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    internal fun postDecode(task: () -> Unit) {
        decoder.execute(task)
    }

    companion object {
        private const val MODEL_DIR = "sherpa/paraformer"
        private const val MODEL_PART_PREFIX = "$MODEL_DIR/model.int8.onnx."
        private const val TOKENS = "$MODEL_DIR/tokens.txt"

        // VAD 参数
        private const val VAD_OPEN_DB = -32.0
        private const val VAD_CLOSE_DB = -38.0
        private const val SILENCE_END_MS = 800L
        private const val MAX_SEGMENT_MS = 30_000L
        private const val MIN_SEGMENT_MS = 400L

        // Partial 识别参数（paraformer 非流式，攒够再识）
        private const val PARTIAL_INTERVAL_MS = 1_500L
        private const val PARTIAL_MIN_DURATION_MS = 700L
        private const val PARTIAL_WINDOW_MS = 5_000L

        // 单次模式下未检测到语音的等待上限
        private const val IDLE_TIMEOUT_MS = 12_000L

        private val instanceLock = Any()

        @Volatile
        private var instance: LocalSpeechEngine? = null

        @Volatile
        private var warming = false

        /**
         * assets 中是否存在本地模型分片（不含初始化开销，可安全用于 isAvailable）。
         *
         * 模型体积（81MB）超过 GitHub API 单文件上传上限，故以 model.int8.onnx.N 分片提交，
         * 首次运行时由 [ensureModelFile] 拼装到 filesDir 后再加载。
         */
        fun isModelPresent(context: Context): Boolean = runCatching {
            context.assets.open("${MODEL_PART_PREFIX}0").use { it.read() >= 0 }
        }.getOrDefault(false)

        /** 预热：应用启动时在后台线程构建引擎，避免首次点麦克风时阻塞主线程。 */
        fun warmUp(context: Context) {
            val appContext = context.applicationContext
            if (warming || instance != null) return
            warming = true
            Thread {
                runCatching { ensureInstance(appContext) }
                warming = false
            }.apply { isDaemon = true }.start()
        }

        /** 获取引擎实例；不存在则同步初始化（首次调用会阻塞至模型拼装+加载完成）。失败返回 null。 */
        fun tryCreate(context: Context): LocalSpeechEngine? = ensureInstance(context.applicationContext)

        /** 把 assets 里的模型分片拼装成 filesDir 下的完整 model.int8.onnx，并复制 tokens.txt。 */
        private fun ensureModelFile(context: Context): File? = runCatching {
            val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
            val target = File(dir, "model.int8.onnx")
            if (!(target.exists() && target.length() > 0)) {
                val parts = (0..9)
                    .map { "$MODEL_PART_PREFIX$it" }
                    .filter { part -> runCatching { context.assets.open(part).close(); true }.getOrDefault(false) }
                if (parts.isEmpty()) return null
                val tmp = File(dir, "model.int8.onnx.tmp")
                tmp.outputStream().use { out ->
                    parts.forEach { part ->
                        context.assets.open(part).use { it.copyTo(out) }
                    }
                }
                if (tmp.length() > 0 && tmp.renameTo(target)) {
                    // assembled
                } else {
                    tmp.delete()
                    return null
                }
            }
            val tokensFile = File(dir, "tokens.txt")
            if (!tokensFile.exists()) {
                context.assets.open(TOKENS).use { input ->
                    tokensFile.outputStream().use { input.copyTo(it) }
                }
            }
            target
        }.getOrNull()

        private fun ensureInstance(context: Context): LocalSpeechEngine? {
            instance?.let { return it }
            synchronized(instanceLock) {
                instance?.let { return it }
                if (!isModelPresent(context)) return null
                val modelFile = ensureModelFile(context) ?: return null
                val tokensFile = File(context.filesDir, "$MODEL_DIR/tokens.txt")
                val engine = runCatching {
                    val config = OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            paraformer = OfflineParaformerModelConfig(model = modelFile.absolutePath),
                            tokens = tokensFile.absolutePath,
                            modelType = "paraformer",
                            numThreads = 2,
                        ),
                    )
                    LocalSpeechEngine(OfflineRecognizer(null, config))
                }.getOrNull()
                instance = engine
                return engine
            }
        }
    }
}

/**
 * 一次本地识别会话。
 *
 * 内部持有一个录音线程（负责 AudioRecord 读取、RMS、VAD 状态机、PCM 累积），
 * 解码请求投递到引擎的单线程 executor；所有事件经 [events] 发送，
 * 自然结束（单次模式出结果 / 连续模式被 stop）时 close 该通道。
 */
internal class LocalSpeechSession(
    private val engine: LocalSpeechEngine,
    private val continuous: Boolean,
    private val events: SendChannel<SpeechEvent>,
) {
    @Volatile
    private var running = true

    @Volatile
    private var stopRequested = false

    @Volatile
    private var activeRecord: AudioRecord? = null

    private val volumeStep = 0.04f
    private var lastVolume = -1f

    // 解码队列背压：同一时刻只允许一个 Partial 在途
    @Volatile
    private var partialInFlight = false

    fun start() {
        Thread { run() }.apply { isDaemon = true; name = "sherpa-record" }.start()
    }

    /** 优雅停止：若正有语音段，先识别并送出 Final，然后关闭；否则直接关闭。 */
    fun stop() {
        stopRequested = true
        releaseRecord()
    }

    /** 立即取消：不送任何事件，直接关闭。 */
    fun cancel() {
        running = false
        stopRequested = true
        releaseRecord()
        events.close()
    }

    private fun releaseRecord() {
        // 解除阻塞的 read()，加速帧边界退出
        runCatching { activeRecord?.stop() }
    }

    private fun run() {
        val record = createAudioRecord()
        if (record == null) {
            events.trySend(SpeechEvent.Failed(SpeechError.AUDIO, SpeechRecognizer.ERROR_AUDIO))
            events.close()
            return
        }
        activeRecord = record
        val buf = ShortArray(LOCAL_FRAME_SAMPLES)
        val segment = FloatAccum(initialCapacity = LOCAL_SAMPLE_RATE / 5)
        var inSpeech = false
        var segmentSeq = 0L
        var segmentStartMs = 0L
        var lastVoiceMs = 0L
        var lastPartialAt = 0L
        var idleSince = System.currentTimeMillis()
        // 为 true 时：单次模式或 stop 场景下，解码任务负责在送出 Final 后 close 通道，
        // 录音线程不再 close，避免解码任务投递前通道被提前关闭导致事件丢失。
        var leaveChannelOpen = false

        fun emitFinal(pcm: FloatArray, closeAfter: Boolean) {
            engine.postDecode {
                val text = engine.decode(pcm)
                if (text.isNotEmpty()) {
                    events.trySend(SpeechEvent.Final(text))
                } else {
                    events.trySend(
                        SpeechEvent.Failed(SpeechError.NO_MATCH, SpeechRecognizer.ERROR_NO_MATCH),
                    )
                }
                if (closeAfter) {
                    events.close()
                    running = false
                }
            }
        }

        try {
            record.startRecording()
            while (running) {
                if (stopRequested && !inSpeech) {
                    if (!continuous) events.trySend(SpeechEvent.Timeout)
                    break
                }
                val n = record.read(buf, 0, LOCAL_FRAME_SAMPLES)
                if (n <= 0) {
                    if (running && !stopRequested) {
                        events.trySend(SpeechEvent.Failed(SpeechError.AUDIO, SpeechRecognizer.ERROR_AUDIO))
                        break
                    }
                    // stop/cancel 打断了 read()：若有足够长的语音段，先识别并送出 Final
                    if (inSpeech) {
                        val segmentMs = System.currentTimeMillis() - segmentStartMs
                        if (segmentMs >= MIN_SEGMENT_MS) {
                            val pcm = segment.snapshot()
                            val closeAfter = !continuous || stopRequested
                            emitFinal(pcm, closeAfter)
                            if (closeAfter) leaveChannelOpen = true
                        }
                    }
                    break
                }
                val now = System.currentTimeMillis()
                val db = frameDb(buf, n)
                emitVolume(db)

                if (!inSpeech) {
                    if (db >= VAD_OPEN_DB) {
                        inSpeech = true
                        segmentSeq += 1
                        segment.clear()
                        segmentStartMs = now
                        lastVoiceMs = now
                        lastPartialAt = now
                    } else {
                        if (!continuous && now - idleSince >= IDLE_TIMEOUT_MS) {
                            events.trySend(SpeechEvent.Timeout)
                            break
                        }
                        continue
                    }
                }

                appendSamples(segment, buf, n)
                if (db >= VAD_CLOSE_DB) lastVoiceMs = now

                val segmentMs = now - segmentStartMs

                // 停止且不足最小语音时长：直接丢弃并关闭
                if (stopRequested && segmentMs < MIN_SEGMENT_MS) {
                    leaveChannelOpen = false
                    break
                }

                // Partial：每 1.5s 用尾窗跑一次（非阻塞，投递到解码线程）
                if (
                    segmentMs >= PARTIAL_MIN_DURATION_MS &&
                    now - lastPartialAt >= PARTIAL_INTERVAL_MS &&
                    !partialInFlight
                ) {
                    lastPartialAt = now
                    val tail = segment.tailMs(PARTIAL_WINDOW_MS)
                    if (tail.size >= LOCAL_SAMPLE_RATE / 4) {
                        val seq = segmentSeq
                        partialInFlight = true
                        engine.postDecode {
                            try {
                                if (running && seq == segmentSeq) {
                                    val text = engine.decode(tail)
                                    if (text.isNotEmpty() && running) {
                                        events.trySend(SpeechEvent.Partial(text))
                                    }
                                }
                            } finally {
                                partialInFlight = false
                            }
                        }
                    }
                }

                val forceFinal = segmentMs >= MAX_SEGMENT_MS
                val endOfSpeech = segmentMs >= MIN_SEGMENT_MS && now - lastVoiceMs >= SILENCE_END_MS
                val stopping = stopRequested && segmentMs >= MIN_SEGMENT_MS

                if (forceFinal || endOfSpeech || stopping) {
                    val pcm = segment.snapshot()
                    val closeAfter = !continuous || stopRequested
                    inSpeech = false
                    idleSince = now
                    emitFinal(pcm, closeAfter)
                    if (closeAfter) {
                        leaveChannelOpen = true
                        break
                    }
                    // 连续模式：清空累积，等待下一段
                    segment.clear()
                }
            }
        } catch (e: SecurityException) {
            events.trySend(
                SpeechEvent.Failed(
                    SpeechError.INSUFFICIENT_PERMISSIONS,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                ),
            )
        } catch (e: Throwable) {
            events.trySend(SpeechEvent.Failed(SpeechError.AUDIO, SpeechRecognizer.ERROR_AUDIO))
        } finally {
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
            activeRecord = null
            if (!leaveChannelOpen) {
                events.close()
            }
            running = false
        }
    }

    private fun emitVolume(db: Double) {
        val level = levelForDb(db)
        val shaped = max(level, lastVolume * 0.6f)
        if (abs(shaped - lastVolume) < volumeStep) return
        lastVolume = shaped
        events.trySend(SpeechEvent.Volume(shaped))
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBuf = runCatching {
            AudioRecord.getMinBufferSize(
                LOCAL_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(LOCAL_FRAME_SAMPLES * 2)
        val bufSize = max(minBuf, LOCAL_FRAME_SAMPLES * 4)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    LOCAL_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec.release() }
        }
        return null
    }

    private fun frameDb(buf: ShortArray, count: Int): Double {
        if (count <= 0) return -80.0
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buf[i] / 32768.0
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        return 20 * log10(rms.coerceAtLeast(1e-7))
    }

    private fun appendSamples(acc: FloatAccum, buf: ShortArray, count: Int) {
        for (i in 0 until count) {
            acc.add(buf[i] / 32768.0f)
        }
    }
}

/** 可增长的 Float 样本累积器（PCM 片段），提供快照与尾窗提取。 */
private class FloatAccum(initialCapacity: Int) {
    private var array = FloatArray(initialCapacity.coerceAtLeast(64))
    private var size = 0

    fun add(value: Float) {
        if (size == array.size) {
            array = array.copyOf(array.size * 2)
        }
        array[size++] = value
    }

    fun clear() {
        size = 0
    }

    fun snapshot(): FloatArray = array.copyOf(size)

    fun tailMs(ms: Int): FloatArray {
        val count = LOCAL_SAMPLE_RATE * ms / 1_000
        val n = count.coerceAtMost(size)
        val result = FloatArray(n)
        System.arraycopy(array, size - n, result, 0, n)
        return result
    }
}
