package io.github.whywood.deepdeep.graph

import io.github.whywood.deepdeep.data.DevPackageManager
import io.github.whywood.deepdeep.data.DevPackageNotifier
import io.github.whywood.deepdeep.data.DevPackageStrings
import io.github.whywood.deepdeep.life.LifeActivityRunner
import io.github.whywood.deepdeep.life.LifeEngine
import io.github.whywood.deepdeep.life.LifeStore
import io.github.whywood.deepdeep.life.LifeTexts
import io.github.whywood.deepdeep.life.loadConfiguredSources
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

    val lifeActivityRunner: LifeActivityRunner by lazy {
        LifeActivityRunner(
            texts = LifeTexts { resId, args -> deps.uiContext.getString(resId, *args) },
            sources = loadConfiguredSources(File(deps.appContext.filesDir, "life/feeds.json")),
        )
    }

    /**
     * 生活引擎 ——「女儿」的大脑。
     * 自主循环：每 tick 选一件事 → 交给 lifeActivityRunner 真实执行（逛 RSS / 学习 / 写作 / 休息）。
     */
    val lifeEngine: LifeEngine by lazy {
        LifeEngine(
            store = LifeStore(File(deps.appContext.filesDir, "life/life.json")),
            scope = deps.scope,
            runActivity = { choice ->
                lifeActivityRunner.run(
                    choice = choice,
                    interests = lifeEngine.state.interests,
                    state = lifeEngine.state,
                )
            },
        ).also { it.start() }
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
