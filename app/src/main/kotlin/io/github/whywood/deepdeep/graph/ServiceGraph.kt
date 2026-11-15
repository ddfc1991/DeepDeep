package io.github.whywood.deepdeep.graph

import io.github.whywood.deepdeep.data.DevPackageManager
import io.github.whywood.deepdeep.data.DevPackageNotifier
import io.github.whywood.deepdeep.data.DevPackageStrings
import io.github.whywood.deepdeep.life.LifeEngine
import io.github.whywood.deepdeep.life.LifeStore
import io.github.whywood.deepdeep.runtime.JobRegistry
import io.github.whywood.deepdeep.runtime.TimerService
import io.github.whywood.deepdeep.service.LinuxJobRunner
import io.github.whywood.deepdeep.service.NotificationCenter
import io.github.whywood.deepdeep.service.ProcessRuntime
import java.io.File

internal class ServiceGraph(private val deps: ServiceGraphDeps) {
    val notificationCenter: NotificationCenter by lazy {
        NotificationCenter(deps.uiContext, deps.host(), deps.conversationStore(), deps.questionCenter.pending)
    }

    val timerService: TimerService by lazy {
        TimerService(
            deps.host(),
            deps.timerStore,
            deps.scope,
            intentsFile = File(deps.appContext.filesDir, "timers/delivery-intents.json"),
        )
    }

    val jobRegistry: JobRegistry by lazy {
        JobRegistry(
            scope = deps.scope,
            notifier = { sessionId, notice, prompt ->
                deps.host().deliverBackgroundTurn(sessionId, notice, prompt, io.github.whywood.deepdeep.runtime.TurnSource.JOB)
            },
            wakeEnabled = { deps.jobWake.value },
            runnerFactory = { cwd -> LinuxJobRunner(deps.hostEnvironment, cwd) },
            maxRunningPerSession = { 10 },
        )
    }

    /**
     * 生活引擎 ——「女儿」的大脑。
     * 自主循环：每 30 分钟 tick 选一件事 → jobRegistry 跑 linux 执行（逛街/签到/学习）。
     */
    val lifeEngine: LifeEngine by lazy {
        LifeEngine(
            store = LifeStore(File(deps.appContext.filesDir, "life/life.json")),
            scope = deps.scope,
            runActivity = { choice ->
                // 把生活决策翻译成可执行的 linux 任务（DeepDeep 容器能力）
                val command = lifeCommandFor(choice)
                if (command.isBlank()) {
                    "自主活动「${choice.reason}」已记录（无执行命令）"
                } else {
                    val start = jobRegistry.start("life", command, "生活：${choice.action.zh}")
                    runCatching { jobRegistry.read(start.jobId).output }.getOrElse { "已启动" }
                }
            },
        ).also { it.start() }
    }

    /** 生活决策 → linux 命令（DeepDeep 沙箱里能跑的真实动作） */
    private fun lifeCommandFor(choice: io.github.whywood.deepdeep.life.LifeChoice): String =
        when (choice.action) {
            io.github.whywood.deepdeep.life.LifeAction.RESEARCH ->
                "echo '[生活] ${choice.reason}' && date"
            io.github.whywood.deepdeep.life.LifeAction.WRITING ->
                "echo '[生活] 写作：${choice.reason}' && date"
            io.github.whywood.deepdeep.life.LifeAction.EXPLORE ->
                "echo '[生活] 探索：${choice.reason}' && date"
            io.github.whywood.deepdeep.life.LifeAction.REST ->
                ""
        }

    val devPackageManager: DevPackageManager by lazy {
        DevPackageManager(
            strings = DevPackageStrings { resId, args -> deps.uiContext.getString(resId, *args) },
            hostEnvironment = deps.hostEnvironment,
            cwd = deps.devPackagesCwd,
            ready = deps.devPackagesReady,
            notifier = object : DevPackageNotifier {
                override fun clearInstallNotification() = notificationCenter.clearInstallNotification()

                override fun notifyInstall(label: String, stage: String?, progress: Int?) =
                    notificationCenter.notifyInstall(label, stage, progress)

                override fun notifyInstallFinished(label: String, success: Boolean, error: String?) =
                    notificationCenter.notifyInstallFinished(label, success, error)
            },
        )
    }

    val processRuntime: ProcessRuntime by lazy {
        ProcessRuntime(
            scope = deps.scope,
            prepare = deps.prepare,
            installBundledSkills = deps.installBundledSkills,
            startPlugins = { deps.pluginManager().start() },
            refreshPluginsIfStale = { deps.pluginManager().refreshIfStale(it) },
            reconfigureHost = { deps.host().reconfigure() },
            ensureHostStarted = { deps.host().ensureStarted() },
            syncSkills = { deps.skillRepository.syncIfChanged() },
            diagnosticsDir = File(deps.appContext.filesDir, "diagnostics"),
        )
    }
}
