package reikai.domain.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Both readers save progress through this kernel, so its order is the one rule pinning them alike. */
class ReaderProgressWritesTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a slower earlier write still lands before a later one`() = runTest {
        val landed = mutableListOf<Int>()
        val writes = ReaderProgressWrites(this)

        writes.launch {
            delay(100)
            landed += 1
        }
        writes.launch {
            delay(10)
            landed += 2
        }
        advanceUntilIdle()

        landed shouldBe listOf(1, 2)
    }

    @Test
    fun `awaiting the writes returns only once the one in flight has landed`() = runTest {
        var landed = false
        val writes = ReaderProgressWrites(this)
        writes.launch {
            delay(100)
            landed = true
        }
        runCurrent()

        writes.awaitLanded()

        landed shouldBe true
    }
}
