package reikai.data.novel

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.library.ChapterDates
import reikai.domain.library.ReleaseInterval
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Predicts when [novel] is next due from all of its [chapters] and stores it, the novel side of manga's
 * `UpdateManga.awaitUpdateFetchInterval`. An interval the user set is kept, and then [chapters] is never
 * read; no [window] means today's.
 */
suspend fun updateNovelFetchInterval(
    novel: Novel,
    chapters: suspend () -> List<NovelChapter>,
    novelRepository: NovelRepository,
    window: ClosedRange<Instant>? = null,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: LocalDateTime = Clock.System.now().toLocalDateTime(zone),
) {
    novelRepository.update(novelFetchInterval(novel, chapters, window, zone, now))
}

/**
 * The update [updateNovelFetchInterval] stores: twin of `FetchInterval.withFetchInterval`, pinned by
 * ReleasePredictionConformanceTest.
 */
suspend fun novelFetchInterval(
    novel: Novel,
    chapters: suspend () -> List<NovelChapter>,
    window: ClosedRange<Instant>? = null,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: LocalDateTime = Clock.System.now().toLocalDateTime(zone),
): NovelUpdate {
    val currentWindow = ReleaseInterval.windowOrToday(window, now.date, zone)
    val (interval, nextUpdate) = ReleaseInterval.predict(
        novel.fetchInterval,
        novel.nextUpdate,
        novel.lastUpdate,
        now,
        zone,
        currentWindow,
    ) {
        chapters().let { all -> ChapterDates(all.map { it.dateUpload }, all.map { it.dateFetch }) }
    }
    return NovelUpdate(novel.id) {
        this.nextUpdate = nextUpdate
        fetchInterval = interval
    }
}

/**
 * Manga's sync-time prediction rule, run once after a whole-novel sync. [novel] is the snapshot from
 * before the sync, whose `lastUpdate` the prediction counts from. As manga's sync, an unchanged list
 * still moves a prediction that was forced, never made, or has fallen behind [window].
 */
suspend fun predictNovelFetchInterval(
    novel: Novel,
    listChanged: Boolean,
    manualFetch: Boolean,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    window: ClosedRange<Instant>? = null,
) {
    if (listChanged || ReleaseInterval.needsPrediction(manualFetch, novel.fetchInterval, novel.nextUpdate, window)) {
        updateNovelFetchInterval(novel, { novelChapterRepository.getByNovelId(novel.id) }, novelRepository, window)
    }
}

/**
 * When this novel is next due, or null once it is completed: twin of `Manga.expectedNextUpdate`, pinned by
 * EntryUpdateTwinsConformanceTest.
 */
fun Novel.expectedNextUpdate(): Instant? =
    nextUpdate?.takeIf { status != NovelStatusCode.COMPLETED.toLong() }
