package io.github.whywood.deepdeep

import android.app.Application
import io.github.whywood.deepdeep.data.crash.CrashReporter
import io.github.whywood.deepdeep.design.DdFonts
import io.github.whywood.deepdeep.service.ProcessState
import io.github.whywood.deepdeep.voice.LocalSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

private val fontWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class DeepDeepApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        graph = AppGraph(this)
        graph.processRuntime.startAsync()
        fontWarmupScope.launch { DdFonts.preload(this@DeepDeepApplication) }
        // 本地离线语音引擎预热（sherpa-onnx 模型加载在后台完成，首次点麦克风即可用）
        LocalSpeechEngine.warmUp(this)
        // 内置插件预装：等待进程/插件宿主就绪后安装 assets/plugins/*.ddp（失败不影响启动）
        graph.appScope.launch {
            try {
                graph.processRuntime.state.first {
                    it == ProcessState.STARTED || it == ProcessState.FAILED
                }
                graph.installBundledPlugins()
            } catch (error: Throwable) {
                android.util.Log.w("DeepDeepApplication", "bundled plugin preinstall failed", error)
            }
        }
    }
}
