package reikai.domain.track

import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelDelayedTrackingStore
import reikai.domain.novel.track.TrackNovelChapter
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Both chapter interactors report the trackers a push failed at, which the details mark-read toast
 * reads, a refused push still queues its retry, and a landed one clears it. One case per rule over both types.
 */
class ChapterPushConformanceTest {

    enum class Type {
        MANGA {
            override fun push(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackChapter(
                    getTracks = mockk { coEvery { await(ENTRY_ID) } returns listOf(mangaTrack()) },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.mangaStore,
                )
                return { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) }
            }

            override fun retry(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackChapter(
                    getTracks = mockk {
                        coEvery { await(ENTRY_ID) } returns listOf(mangaTrack(), mangaTrack().copy(id = OTHER_TRACK_ID))
                    },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.mangaStore,
                )
                return { interactor.retry(mockk(), mangaTrack(), CHAPTER) }
            }

            override fun store(h: Harness): DelayedTrackingStore = h.mangaStore
        },
        NOVEL {
            override fun push(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackNovelChapter(
                    getNovelTracks = mockk { coEvery { awaitGroup(ENTRY_ID) } returns listOf(novelTrack()) },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.novelStore,
                )
                return { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) }
            }

            override fun retry(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackNovelChapter(
                    getNovelTracks = mockk {
                        coEvery { awaitGroup(ENTRY_ID) } returns
                            listOf(novelTrack(), novelTrack().copy(id = OTHER_TRACK_ID))
                    },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.novelStore,
                )
                return { interactor.retry(mockk(), novelTrack(), CHAPTER) }
            }

            override fun store(h: Harness): DelayedTrackingStore = h.novelStore
        },
        ;

        abstract fun push(h: Harness): suspend () -> ChapterPushOutcome

        /** Retries the queued [TRACK_ID] while another row of its group, [OTHER_TRACK_ID], is behind too. */
        abstract fun retry(h: Harness): suspend () -> ChapterPushOutcome
        abstract fun store(h: Harness): DelayedTrackingStore
    }

    class Harness(failure: Exception?) {
        val pushedTrackIds = mutableListOf<Long>()
        val tracker = mockk<BaseTracker> {
            every { id } returns TRACKER_ID
            every { isLoggedIn } returns true
            every { supportsReadingDates } returns false
            coEvery { refresh(any()) } answers { firstArg() }
            coEvery { update(any(), any()) } answers {
                failure?.let { throw it }
                firstArg<DbTrack>().also { pushedTrackIds += it.id!! }
            }
        }
        val trackerManager = mockk<TrackerManager> { every { get(TRACKER_ID) } returns tracker }
        val mangaStore = mockk<DelayedTrackingStore>(relaxed = true)
        val novelStore = mockk<NovelDelayedTrackingStore>(relaxed = true)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push the tracker refuses comes back failed`(type: Type) = runTest {
        val h = Harness(RATE_LIMITED)

        type.push(h)() shouldBe ChapterPushOutcome(failed = listOf(h.tracker to RATE_LIMITED))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push the tracker refuses is still queued for retry`(type: Type) = runTest {
        val h = Harness(RATE_LIMITED)

        type.push(h)()

        verify { type.store(h).add(TRACK_ID, CHAPTER) }
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push that lands comes back with no failure`(type: Type) = runTest {
        val h = Harness(failure = null)

        type.push(h)() shouldBe ChapterPushOutcome(failed = emptyList())
    }

    /** The delayed-tracking drain never removes an item itself, so a landed push must, or it retries forever. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push that lands clears its queued retry`(type: Type) = runTest {
        val h = Harness(failure = null)

        type.push(h)()

        verify { type.store(h).remove(TRACK_ID) }
    }

    /**
     * The queue keeps only a row and the number read, not which source of a merged series it was read
     * on, so another row cannot be judged against that number: a server tracker on another source would
     * take a number from the wrong source's numbering.
     */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a queued retry pushes only the row it queued`(type: Type) = runTest {
        val h = Harness(failure = null)

        type.retry(h)()

        h.pushedTrackIds shouldBe listOf(TRACK_ID)
    }

    private companion object {
        const val ENTRY_ID = 7L
        const val TRACK_ID = 1L
        const val OTHER_TRACK_ID = 2L
        const val TRACKER_ID = 3L
        const val CHAPTER = 12.0
        val RATE_LIMITED = HttpException(429)

        fun mangaTrack() = Track(
            id = TRACK_ID,
            mangaId = ENTRY_ID,
            trackerId = TRACKER_ID,
            remoteId = 9L,
            libraryId = null,
            title = "title",
            lastChapterRead = 5.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = null,
            finishDate = null,
            private = false,
        )

        fun novelTrack() = NovelTrack(
            id = TRACK_ID,
            novelId = ENTRY_ID,
            trackerId = TRACKER_ID,
            remoteId = 9L,
            libraryId = null,
            title = "title",
            lastChapterRead = 5.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = null,
            finishDate = null,
            private = false,
        )
    }
}
