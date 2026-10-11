package reikai.domain.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A reader's progress writes, which outlive it on [scope], landing in the order they were launched. The
 * lock is taken before a write's first suspension and the mutex is fair, so a write that suspends longer
 * cannot let a later page's land first. Both readers save through one, so they keep the same order.
 */
class ReaderProgressWrites(private val scope: CoroutineScope) {

    private val lock = Mutex()

    /** Launches [write] behind every write launched before it. Called on the main thread, as page turns are. */
    fun launch(write: suspend () -> Unit): Job = scope.launch(Dispatchers.Main.immediate) { lock.withLock { write() } }

    /** Returns once every write launched so far has landed, as the pending deletion on leaving needs. */
    suspend fun awaitLanded() = lock.withLock {}
}
