package eu.kanade.tachiyomi.data.download

import android.app.Application
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.download.FakeNotificationShade
import reikai.domain.entry.EntryId
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

class DownloadNotifierTest {

    private val shade = FakeNotificationShade()
    private val manga = Manga.create().copy(id = 1L, title = "Manga")
    private val notifier = DownloadNotifier(
        mockk<Application>(relaxed = true),
        SecurityPreferences(InMemoryPreferenceStore()),
        mockk { coEvery { await(EntryId.Manga(1L)) } returns CustomMangaInfo(mangaId = 1L, title = "Mine") },
    ) {
        mockk { coEvery { adultIdsAmong(any()) } returns emptySet() }
    }

    @AfterEach
    fun tearDown() {
        shade.close()
    }

    /** Each error notice is built fresh, so it carries nothing over from an earlier one (mihonapp/mihon#3341). */
    @Test
    fun `an error after a warning shows none of the warning's expanded text`() = runTest {
        notifier.onWarning("warning")

        notifier.onError("error", "Ch 10", manga)

        val shown = shade.shown.getValue(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)!!
        verify(exactly = 0) { shown.setStyle(any()) }
    }

    /**
     * A chapter name often repeats the source title, which is stripped before the Edit info title leads. That
     * the title is the Edit info one is pinned for both types by DownloadNoticeTitleConformanceTest.
     */
    @Test
    fun `the progress notice drops the source title a chapter name repeats`() = runTest {
        val chapter = Chapter.create().copy(id = 10L, mangaId = 1L, name = "Manga - Ch 10")
        val download = Download(mockk(), manga, chapter).apply { pages = emptyList() }

        notifier.onProgressChange(download)

        shade.titleOf(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS) shouldBe "Mine - Ch 10"
    }

    /** Every downloaded page reports, faster than the system shows a notice anyway (mihonapp/mihon#4071). */
    @Test
    fun `a page's progress within half a second of the last notice is skipped`() = runTest {
        notifier.onProgressChange(inProgress("Manga - Ch 10"))
        shade.now += 499

        notifier.onProgressChange(inProgress("Manga - Ch 11"))

        shade.titleOf(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS) shouldBe "Mine - Ch 10"
    }

    @Test
    fun `a finished chapter's notice is never skipped`() = runTest {
        notifier.onProgressChange(inProgress("Manga - Ch 10"))

        notifier.onProgressChange(
            inProgress("Manga - Ch 11").apply {
                pages!!.forEach { it.status = Page.State.Ready }
            },
        )

        shade.titleOf(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS) shouldBe "Mine - Ch 11"
    }

    private fun inProgress(name: String): Download {
        val chapter = Chapter.create().copy(id = 10L, mangaId = 1L, name = name)
        return Download(mockk(), manga, chapter).apply { pages = listOf(Page(0), Page(1)) }
    }
}
