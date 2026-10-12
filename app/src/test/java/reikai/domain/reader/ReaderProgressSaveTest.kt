package reikai.domain.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Both readers hold their position back through this kernel, so its timing is the one rule pinning them alike. */
class ReaderProgressSaveTest {

    @Test
    fun `a burst of page turns writes once, the last page, after the delay`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        (1..5).forEach { save.report(1L, it) }
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds + 1)

        written shouldBe listOf(1L to 5)
    }

    @Test
    fun `nothing is written before the delay runs out`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 3)
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds - 1)

        written shouldBe emptyList()
    }

    @Test
    fun `a position that finishes the chapter is written at once`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 3)
        save.report(1L, 9, now = true)
        runCurrent()

        written shouldBe listOf(1L to 9)
    }

    @Test
    fun `a flush on pause writes the held position without waiting`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 4)
        save.flush()

        written shouldBe listOf(1L to 4)
    }

    @Test
    fun `a page in another chapter writes the held one first`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 7)
        save.report(2L, 0)

        written shouldBe listOf(1L to 7)
    }

    @Test
    fun `a discarded position is never written`() = runTest {
        val written = mutableListOf<Pair<Long, Int>>()
        val save = ReaderProgressSave<Long, Int>(backgroundScope) { id, page -> written += id to page }

        save.report(1L, 7)
        save.discardIf { it == 1L }
        advanceTimeBy(PROGRESS_SAVE_DELAY.inWholeMilliseconds + 1)

        written shouldBe emptyList()
    }
}
