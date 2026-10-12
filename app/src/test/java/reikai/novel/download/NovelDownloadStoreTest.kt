package reikai.novel.download

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** Removing saved queue rows, which a delete of many chapters does for every one of them. */
class NovelDownloadStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = NovelDownloadStore(
        mockk<Context> { every { getSharedPreferences(any(), any()) } returns prefs },
        mockk(),
    )

    private val saved = (1L..4L).map { NovelDownload(novelId = 1L, chapterId = it, url = "/c/$it") }

    @Test
    fun `building the store does not read the saved queue`() {
        var opened = false
        NovelDownloadStore(
            mockk<Context> {
                every { getSharedPreferences(any(), any()) } answers {
                    opened = true
                    prefs
                }
            },
            mockk(),
        )

        opened shouldBe false
    }

    @Test
    fun `a row saved by a store built after a restart is ordered after the saved rows`() {
        store.addAll(saved.take(2))
        val restarted =
            NovelDownloadStore(mockk<Context> { every { getSharedPreferences(any(), any()) } returns prefs }, mockk())

        restarted.addAll(saved.drop(2).take(1))

        restarted.persisted().sortedBy { it.order }.map { it.chapterId } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `removing rows keeps the rows left out`() {
        store.addAll(saved)

        store.removeAll(listOf(1L, 2L, 3L))

        store.persisted().map { it.chapterId } shouldBe listOf(4L)
    }

    @Test
    fun `removing many rows writes the saved queue once`() {
        store.addAll(saved)
        val before = prefs.writes

        store.removeAll(listOf(1L, 2L, 3L))

        prefs.writes - before shouldBe 1
    }
}
