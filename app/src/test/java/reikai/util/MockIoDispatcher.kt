package reikai.util

import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import mihon.core.migration.Migrator

/**
 * Stubs Dispatchers.IO with [dispatcher] until `unmockkStatic(Dispatchers::class)`. An object that binds
 * IO on first use would keep the stub for the rest of the test JVM, so those are first used here on the
 * real one: Migrator's scope is, and a later test's migration otherwise waits on a finished scheduler.
 */
fun mockIoDispatcher(dispatcher: CoroutineDispatcher) {
    check(Migrator.scope.isActive)
    mockkStatic(Dispatchers::class)
    every { Dispatchers.IO } returns dispatcher
}
