package reikai.quickjs

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val creation = Mutex()

/**
 * Builds a dokar QuickJs engine, one at a time across the whole process. Its native initGlobals caches
 * class handles as process-wide JNI global refs, so two creations at once abort the runtime with
 * "jobject is an invalid global reference". Every engine, the LN plugin host's and the extensions' Cash
 * shim's alike, is created here, since a lock held by only one of them leaves the other racing it.
 */
suspend fun createQuickJs(dispatcher: CoroutineDispatcher): QuickJs = creation.withLock { QuickJs.create(dispatcher) }
