package io.github.whywood.deepdeep.ui.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.whywood.deepdeep.core.AppDependencies
import io.github.whywood.deepdeep.life.LifeStateData
import io.github.whywood.deepdeep.ui.life.LifeScreen
import kotlinx.coroutines.delay

@Composable
internal fun LifeRoute(
    dependencies: AppDependencies,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<LifeStateData?>(null) }
    LaunchedEffect(dependencies.lifeEngine) {
        while (true) {
            state = dependencies.lifeEngine.state
            delay(30_000L)
        }
    }
    LifeScreen(
        summary = state?.let { dependencies.lifeEngine.summary() },
        state = state,
        onBack = onBack,
        onRefresh = { state = dependencies.lifeEngine.state },
        isRunning = dependencies.lifeEngine.isRunning,
        onAddGoal = { title ->
            dependencies.lifeEngine.addGoal(title)
            state = dependencies.lifeEngine.state
        },
        onComfort = {
            val reply = dependencies.lifeEngine.onUserText("我来看看你")
            state = dependencies.lifeEngine.state
            reply
        },
    )
}
