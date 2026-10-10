package reikai.presentation.reader

import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The reader's trim behind it ("after reading automatically delete", no slots kept) also deletes the
 * copies "mark duplicate read" marked read along with the chapter it retires (mihonapp/mihon#3030), by
 * the shared [reikai.domain.reader.duplicatesOfRead] rule. Pinned once over both readers.
 *
 * One entry lists chapters 1, 2 and 3 by group "x" and a copy of 2 by group "y"; the reader opens on 1
 * and two Nexts finish 1 and 2. Each type answers with the chapters queued for deletion, by label.
 */
class DeleteDuplicatesBehindReaderConformanceTest {

    interface Probe {
        suspend fun queuedForDeletion(scope: TestScope, markDuplicates: Boolean): Set<String>
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a copy marked read with a retired chapter is deleted with it`(probe: Probe) = runTest {
        probe.queuedForDeletion(this, markDuplicates = true) shouldBe setOf("1", "2", "2y")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `without mark duplicate read only the retired chapters are deleted`(probe: Probe) = runTest {
        probe.queuedForDeletion(this, markDuplicates = false) shouldBe setOf("1", "2")
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        private fun markDuplicateSetting(on: Boolean) = buildSet {
            add(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)
            if (on) add(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun queuedForDeletion(scope: TestScope, markDuplicates: Boolean): Set<String> =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0, scanlator = "x")
                harness.chapter(20L, manga, 2.0, scanlator = "x")
                harness.chapter(21L, manga, 2.0, scanlator = "y")
                harness.chapter(30L, manga, 3.0, scanlator = "x")
                val labels = mapOf(10L to "1", 20L to "2", 21L to "2y", 30L to "3")
                val queued = mutableSetOf<String>()
                val prefs = mapOf(
                    "mark_read_on_skip" to true,
                    "skip_dupe" to true,
                    "remove_after_read_slots" to 0,
                    "mark_duplicate_read_chapter_read" to markDuplicateSetting(markDuplicates),
                )
                harness.open(
                    manga,
                    chapterId = 10L,
                    preferences = prefs,
                    onEnqueueDelete = { chapters -> chapters.forEach { queued += labels.getValue(it.id) } },
                ) { model, _ ->
                    model.nextAndSettle()
                    model.nextAndSettle()
                }
                queued
            }

        /** The trim is queued on the IO dispatcher, which virtual time does not reach, so it is waited out. */
        private suspend fun ReaderViewModel.nextAndSettle() {
            val scopeJob = viewModelScope.coroutineContext.job
            val running = scopeJob.children.toSet()
            loadNextChapter()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { (scopeJob.children.toSet() - running).joinAll() }
            }
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun queuedForDeletion(scope: TestScope, markDuplicates: Boolean): Set<String> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.readerMarkReadOnSkip().set(true)
                harness.novelPreferences.readerSkipDuplicateChapters().set(true)
                harness.novelPreferences.removeAfterReadSlots().set(0)
                harness.libraryPreferences.markDuplicateReadChapterAsRead.set(markDuplicateSetting(markDuplicates))
                val novel = harness.novel(harness.source("src"))
                val one = harness.chapter(novel, 1.0, scanlator = "x")
                val two = harness.chapter(novel, 2.0, scanlator = "x")
                val copy = harness.chapter(novel, 2.0, url = "/2-y", sourceOrder = 3L, scanlator = "y")
                val three = harness.chapter(novel, 3.0, sourceOrder = 4L, scanlator = "x")
                val labels = mapOf(one.id to "1", two.id to "2", copy.id to "2y", three.id to "3")
                val model = harness.open(novel, one.id)
                scope.advanceUntilIdle()

                model.nextChapter()
                scope.advanceUntilIdle()
                model.nextChapter()
                scope.advanceUntilIdle()

                harness.pendingDeletes.mapTo(mutableSetOf()) { labels.getValue(it.id) }
            }
    }
}
