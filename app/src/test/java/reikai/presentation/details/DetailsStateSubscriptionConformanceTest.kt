package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import eu.kanade.tachiyomi.ui.manga.seedManga
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.mockIoDispatcher
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Each details model holds its chapter queries open only while its state is collected, plus five seconds,
 * as upstream's stateIn(WhileSubscribed(5.seconds)) does: one rule, pinned once over both types. Each probe
 * opens a real model and counts live collectors of the opened entry's chapter query. The models hard-code
 * Dispatchers.IO (and manga Default), so both point at the test dispatcher to keep time virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsStateSubscriptionConformanceTest {

    /** A details model opened on one stored entry with nothing collecting its state yet. */
    interface Probe {
        suspend fun opened(
            scope: TestScope,
            dispatcher: TestDispatcher,
            block: suspend (state: Flow<*>, activeQueries: () -> Int) -> Unit,
        )
    }

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockIoDispatcher(dispatcher)
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `nothing collecting the state leaves the chapter query unsubscribed`(probe: Probe) = runTest(dispatcher) {
        probe.opened(this, dispatcher) { _, active ->
            advanceUntilIdle()

            active() shouldBe 0
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a collected state holds the chapter query open`(probe: Probe) = runTest(dispatcher) {
        probe.opened(this, dispatcher) { state, active ->
            backgroundScope.launch { state.collect {} }
            advanceUntilIdle()

            active() shouldBe 1
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the chapter query is still held just under five seconds after the last collector leaves`(probe: Probe) =
        runTest(dispatcher) {
            probe.opened(this, dispatcher) { state, active ->
                val collector = launch { state.collect {} }
                advanceUntilIdle()

                collector.cancel()
                advanceTimeBy(4.9.seconds)
                runCurrent()

                active() shouldBe 1
            }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the chapter query is released five seconds after the last collector leaves`(probe: Probe) =
        runTest(dispatcher) {
            probe.opened(this, dispatcher) { state, active ->
                val collector = launch { state.collect {} }
                advanceUntilIdle()

                collector.cancel()
                advanceTimeBy(5.seconds)
                runCurrent()

                active() shouldBe 0
            }
        }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun opened(
            scope: TestScope,
            dispatcher: TestDispatcher,
            block: suspend (state: Flow<*>, activeQueries: () -> Int) -> Unit,
        ) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            val chapters = CountingChapters(ChapterRepositoryImpl(database), watched = MANGA_ID)
            driver.seedManga(MANGA_ID, chapterId = 10L)
            val store = ViewModelStore()
            try {
                val model = mangaDetailsModel(
                    MANGA_ID,
                    MangaRepositoryImpl(database),
                    chapters,
                    longArrayOf(MANGA_ID),
                ) {
                    every { Dispatchers.Default } returns dispatcher
                }.also { store.put("manga", it) }
                block(model.state, chapters.active::get)
            } finally {
                store.clear()
                scope.advanceUntilIdle()
                driver.close()
            }
        }

        /** Counts live collectors of [watched]'s chapter flow, the query the details list holds open. */
        private class CountingChapters(
            private val delegate: ChapterRepository,
            private val watched: Long,
        ) : ChapterRepository by delegate {
            val active = AtomicInteger()

            override suspend fun getChapterByMangaIdAsFlow(
                mangaId: Long,
                applyScanlatorFilter: Boolean,
            ): Flow<List<Chapter>> {
                val flow = delegate.getChapterByMangaIdAsFlow(mangaId, applyScanlatorFilter)
                if (mangaId != watched) return flow
                return flow.onStart { active.incrementAndGet() }.onCompletion { active.decrementAndGet() }
            }
        }

        private companion object {
            const val MANGA_ID = 1L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun opened(
            scope: TestScope,
            dispatcher: TestDispatcher,
            block: suspend (state: Flow<*>, activeQueries: () -> Int) -> Unit,
        ) = NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val novel = harness.novel(harness.source("alpha"))
            harness.chapter(novel, 1.0)
            block(harness.openDetails(novel, shown = false).state, harness.chapterQueries::get)
        }
    }
}
