package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.manga.MangaMergeManager
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** A chapter read on one source of a merged series, where a server tracker is bound on another. */
class TrackChapterTest {

    private val pushedToServer = mutableListOf<Double>()

    private val server = mockk<BaseTracker>(relaxed = true, moreInterfaces = arrayOf(EnhancedTracker::class)) {
        every { id } returns SERVER_ID
        every { isLoggedIn } returns true
        coEvery { refresh(any()) } answers { firstArg() }
        coEvery { update(any(), any()) } answers {
            firstArg<DbTrack>().also { pushedToServer += it.last_chapter_read }
        }
    }

    private val trackChapter = TrackChapter(
        getTracks = GetTracksInGroup(
            preferences = ReikaiLibraryPreferences(EmittingPreferenceStore()),
            getTracks = mockk<GetTracks> {
                coEvery { await(WEB_COPY) } returns emptyList()
                coEvery { await(SERVER_COPY) } returns listOf(serverRow())
            },
            mergeManager = mockk<MangaMergeManager> {
                every { relatedIdsChanges() } returns flowOf(Unit)
                coEvery { relatedIdsList(any()) } returns listOf(WEB_COPY, SERVER_COPY)
            },
        ),
        trackerManager = mockk<TrackerManager> { every { get(SERVER_ID) } returns server },
        upsertTrack = mockk(relaxed = true),
        delayedTrackingStore = mockk(relaxed = true),
    )

    @Test
    fun `a chapter read on a sibling source is not pushed to the server`() = runTest {
        trackChapter.await(mockk<Context>(relaxed = true), WEB_COPY, 40.0)

        pushedToServer.shouldBeEmpty()
    }

    @Test
    fun `a chapter read on the source the server serves is pushed to it`() = runTest {
        trackChapter.await(mockk<Context>(relaxed = true), SERVER_COPY, 40.0)

        pushedToServer shouldContainExactly listOf(40.0)
    }

    private fun serverRow() = Track(
        id = 5L,
        mangaId = SERVER_COPY,
        trackerId = SERVER_ID,
        remoteId = 9L,
        libraryId = null,
        title = "t",
        lastChapterRead = 10.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = null,
        finishDate = null,
        private = false,
    )

    private companion object {
        const val WEB_COPY = 1L
        const val SERVER_COPY = 2L
        const val SERVER_ID = 6L
    }
}
