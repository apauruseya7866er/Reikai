package reikai.novel.host

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import reikai.quickjs.createQuickJs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A manga extension's Cash engine and a plugin slot's engine created at the same moment once aborted
 * the process ("jobject is an invalid global reference"), since only the plugin host held the lock.
 * A crash here takes the instrumentation down rather than failing an assert.
 */
@RunWith(AndroidJUnit4::class)
class QuickJsCreationTest {

    @Test
    fun cashAndPluginEnginesCreatedTogetherAllRun() {
        val start = CountDownLatch(1)
        val results = IntArray(ENGINES)
        val threads = (0 until ENGINES).map { i ->
            thread {
                start.await()
                results[i] = if (i % 2 == 0) cashEngine() else pluginEngine()
            }
        }
        start.countDown()
        threads.forEach { it.join(TIMEOUT_MS) }

        assertEquals(List(ENGINES) { 2 }, results.toList())
    }

    private fun cashEngine(): Int = QuickJs.create().use { (it.evaluate("1 + 1") as Number).toInt() }

    private fun pluginEngine(): Int {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            runBlocking(dispatcher) {
                val engine = createQuickJs(dispatcher)
                try {
                    (engine.evaluate<Any?>("1 + 1") as Number).toInt()
                } finally {
                    engine.close()
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private companion object {
        const val ENGINES = 16
        const val TIMEOUT_MS = 30_000L
    }
}
