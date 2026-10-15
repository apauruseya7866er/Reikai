package reikai.novel.install

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.core.metro.AppCoroutineScope
import reikai.novel.registry.LnRegistryEntry
import reikai.util.messageOrName
import tachiyomi.core.common.util.lang.launchIO

/** One source's install as it goes, so a picker can say where it is and what did not work. */
@Immutable
data class LnInstallProgress(
    val running: Boolean = false,
    val installed: Int = 0,
    val total: Int = 0,
    val failed: List<Failed> = emptyList(),
) {
    data class Failed(val id: String, val name: String, val reason: String)
}

/**
 * Installs a picked set of sources in the background, on the application scope, because the work
 * outlives the screen that started it: a reader who picks 171 sources and leaves the picker should
 * not find 30 of them installed when they come back.
 *
 * One at a time and never stopping early. A source that fails is recorded and the run carries on,
 * since a registry of 300 entries will always hold a site that is down. Why: docs/dev/subsystems/ln-plugin-host.md
 */
@Inject
@SingleIn(AppScope::class)
class LnSourceInstaller(
    private val installer: LnPluginInstaller,
    @AppCoroutineScope private val appScope: CoroutineScope,
) {

    private val mutableProgress = MutableStateFlow(LnInstallProgress())

    val progress: StateFlow<LnInstallProgress> = mutableProgress.asStateFlow()

    /** Starts a run and returns at once. A run already going is not restarted. */
    fun install(entries: List<LnRegistryEntry>) {
        if (entries.isEmpty() || mutableProgress.value.running) return
        mutableProgress.value = LnInstallProgress(running = true, total = entries.size)
        appScope.launchIO {
            val failed = ArrayList<LnInstallProgress.Failed>()
            var installed = 0
            entries.forEach { entry ->
                runCatching { installer.installFromUrl(entry.url) }
                    .onSuccess { installed++ }
                    .onFailure { failed += LnInstallProgress.Failed(entry.id, entry.name, it.messageOrName) }
                mutableProgress.value = LnInstallProgress(running = true, installed, entries.size, failed)
            }
            mutableProgress.value = LnInstallProgress(running = false, installed, entries.size, failed)
        }
    }

    /** Clears a finished run's failures, which is what the reader dismisses them by. */
    fun dismissFailures() {
        if (!mutableProgress.value.running) mutableProgress.value = LnInstallProgress()
    }
}
