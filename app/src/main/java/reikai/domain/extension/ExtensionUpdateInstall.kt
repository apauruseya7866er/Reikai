package reikai.domain.extension

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Installs what [findUpdate] returns, fetching the listings again through [refetch] first when the held
 * ones have none, since a store outage keeps a badge with nothing behind it. Still none ends on
 * [unreachable] and never completes: the row clears its state when the flow completes, which would
 * leave the Update button doing nothing again.
 */
fun installUpdate(
    findUpdate: () -> Extension.Available?,
    refetch: suspend () -> Unit,
    unreachable: () -> InstallStep.Error,
    install: (Extension.Available) -> Flow<InstallStep>,
): Flow<InstallStep> = flow {
    val update = findUpdate() ?: run {
        emit(InstallStep.Pending)
        refetch()
        findUpdate()
    }
    if (update == null) emitAll(MutableStateFlow(unreachable())) else emitAll(install(update))
}

/**
 * Runs [block] once for every caller that asked before a run finished, so Update all over several rows
 * the stores dropped costs one fetch rather than one per row.
 */
class SharedRefresh(private val block: suspend () -> Unit) {

    private val mutex = Mutex()

    @Volatile
    private var finishedRuns = 0L

    suspend operator fun invoke() {
        val seen = finishedRuns
        mutex.withLock {
            if (finishedRuns != seen) return
            try {
                block()
            } finally {
                finishedRuns++
            }
        }
    }
}
