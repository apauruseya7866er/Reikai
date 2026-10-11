package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.library.ChapterDates
import reikai.domain.library.ReleaseInterval
import reikai.domain.library.ReleaseSchedule
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Clock
import kotlin.time.Instant

@Inject
class FetchInterval(
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun withFetchInterval(
        manga: Manga,
        dateTime: LocalDateTime,
        timeZone: TimeZone,
        window: ClosedRange<Instant>?,
    ): MangaUpdate {
        // RK --> the prediction lives in reikai.domain.library.ReleaseInterval, which novels call too
        val currentWindow =
            ReleaseInterval.windowOrToday(window, Clock.System.now().toLocalDateTime(timeZone).date, timeZone)
        val (interval, nextUpdate) = ReleaseInterval.predict(
            manga.fetchInterval,
            manga.nextUpdate,
            manga.lastUpdate,
            dateTime,
            timeZone,
            currentWindow,
        ) {
            getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true).toChapterDates()
        }
        // RK <--

        return MangaUpdate(manga.id) {
            this.nextUpdate = nextUpdate
            fetchInterval = interval
        }
    }

    // RK --> the date math lives in reikai.domain.library.ReleaseInterval, which novels call too
    fun getWindow(localDateTime: LocalDate, timeZone: TimeZone): ClosedRange<Instant> =
        ReleaseInterval.window(localDateTime, timeZone)
    // RK <--

    internal fun calculateInterval(chapters: List<Chapter>, zone: TimeZone): Int {
        return estimateSchedule(chapters, zone, Clock.System.now().toLocalDateTime(zone).date).interval
    }

    // RK --> the date math lives in reikai.domain.library.ReleaseInterval, which novels call too
    internal fun estimateSchedule(chapters: List<Chapter>, zone: TimeZone, today: LocalDate): ReleaseSchedule =
        ReleaseInterval.schedule(chapters.toChapterDates(), zone, today)

    private fun List<Chapter>.toChapterDates() = ChapterDates(map { it.dateUpload }, map { it.dateFetch })
    // RK <--

    companion object {
        const val MAX_INTERVAL = ReleaseInterval.MAX_INTERVAL // RK
    }
}

// RK: ReleaseSchedule and ReleasePattern moved to reikai.domain.library (ReleasePattern.kt), shared with novels
