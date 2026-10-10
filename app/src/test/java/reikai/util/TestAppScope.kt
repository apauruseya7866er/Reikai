package reikai.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Stands in for the graph's `@AppCoroutineScope` in a test that builds an app-scoped class by hand. It leaves out
 * the release builds' exception handler, so an uncaught failure still reaches the test runner.
 */
fun testAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
