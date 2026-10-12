package mihon.app.di

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class AppCoroutineScopeTest {

    private val crashed = AtomicReference<Throwable?>()
    private val platformHandler = Thread.getDefaultUncaughtExceptionHandler()

    @BeforeEach
    fun recordCrashes() {
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashed.set(e) }
    }

    @AfterEach
    fun restoreCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(platformHandler)
    }

    @Test
    fun `outside debug builds a failed child is logged instead of crashing the app`() = runTest {
        AppBindings.providesAppCoroutineScope(isDebugBuild = false)
            .launch { error("boom") }
            .join()

        crashed.get().shouldBeNull()
    }

    @Test
    fun `a debug build still crashes on a failed child`() = runTest {
        val boom = IllegalStateException("boom")

        AppBindings.providesAppCoroutineScope(isDebugBuild = true)
            .launch { throw boom }
            .join()

        crashed.get() shouldBe boom
    }
}
