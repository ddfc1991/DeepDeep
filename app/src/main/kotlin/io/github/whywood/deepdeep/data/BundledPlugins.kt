package io.github.whywood.deepdeep.data

import android.content.Context
import android.util.Log
import io.github.whywood.deepdeep.plugin.manager.PluginManagerFacade
import java.io.File

/**
 * Installs plugins shipped inside the APK (assets/plugins/*.ddp) on first launch
 * and upgrades them whenever a newer version is bundled.
 *
 * Install markers are stored in SharedPreferences per plugin id -> version. A
 * matching marker skips the (already installed) plugin; a different marker
 * means the bundled package is newer and triggers an upgrade. Failures are
 * logged and never abort app startup.
 */
class BundledPlugins(
    context: Context,
    private val pluginManager: PluginManagerFacade,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Synchronizes all bundled plugins; returns a summary of what happened. */
    suspend fun sync(): List<String> {
        val outcomes = mutableListOf<String>()
        val names = runCatching { context.assets.list(ASSET_DIR) }
            .getOrNull()
            .orEmpty()
            .filter { it.endsWith(".ddp") }
            .sorted()
        if (names.isEmpty()) return outcomes
        for (name in names) {
            val outcome = installBundled(name)
            outcomes += outcome
            Log.i(TAG, "bundled plugin $name -> $outcome")
        }
        return outcomes
    }

    private suspend fun installBundled(name: String): String {
        return try {
            val file = File(context.cacheDir, "bundled-plugins/$name")
            file.parentFile?.mkdirs()
            context.assets.open("$ASSET_DIR/$name").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val inspection = pluginManager.inspectExternal(file) ?: return "error: plugin manager unavailable"
            if (inspection.error != null || inspection.pluginId.isBlank()) {
                return "error: ${inspection.error ?: "unrecognized package"}"
            }
            val installedVersion = prefs.getString(inspection.pluginId, null)
            if (installedVersion == inspection.version) {
                return "already-installed(${inspection.version})"
            }
            val result = pluginManager.installExternal(file, acceptPublisher = true)
            if (result.success) {
                prefs.edit().putString(inspection.pluginId, inspection.version).apply()
                "installed(${inspection.version})"
            } else {
                "error: ${result.error ?: "install failed"}"
            }
        } catch (error: Throwable) {
            "error: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            runCatching { File(context.cacheDir, "bundled-plugins/$name").delete() }
        }
    }

    private companion object {
        const val TAG = "BundledPlugins"
        const val PREFS_NAME = "bundled_plugins"
        const val ASSET_DIR = "plugins"
    }
}
