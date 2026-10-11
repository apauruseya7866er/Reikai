package reikai.domain.library

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.novelFetchInterval
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * A manga and a novel stored alike, with chapters released on the same days, are predicted the same
 * interval and next update, through each type's own entry point.
 */
class ReleasePredictionConformanceTest {

    @ParameterizedTest
    @EnumSource(Case::class)
    fun `manga and novels predict alike`(case: Case) = runTest {
        novel(case) shouldBe manga(case)
    }

    private suspend fun manga(case: Case): Pair<Int?, Instant?> {
        val chapters = case.days.map { day ->
            Chapter.create().copy(dateUpload = noon(day), dateFetch = noon(day))
        }
        val getChapters = mockk<GetChaptersByMangaId> { coEvery { await(any(), any()) } returns chapters }
        val manga = Manga.create().copy(
            fetchInterval = case.fetchInterval,
            nextUpdate = case.nextUpdate,
            lastUpdate = case.lastUpdate,
        )
        val update = FetchInterval(getChapters).withFetchInterval(manga, case.today.atTime(10, 0), ZONE, case.window)
        return update.fetchInterval to update.nextUpdate
    }

    private suspend fun novel(case: Case): Pair<Int?, Instant?> {
        val chapters = case.days.mapIndexed { index, day ->
            NovelChapter(
                id = index.toLong(), novelId = 1L, url = "/c$index", name = "c", read = false, bookmark = false,
                lastTextProgress = 0L, chapterNumber = index.toDouble(), sourceOrder = 0L, dateFetch = noon(day),
                dateUpload = noon(day), page = "",
            )
        }
        val novel = Novel.create().copy(
            fetchInterval = case.fetchInterval,
            nextUpdate = case.nextUpdate,
            lastUpdate = case.lastUpdate,
        )
        val update = novelFetchInterval(novel, { chapters }, case.window, ZONE, case.today.atTime(10, 0))
        return update.fetchInterval to update.nextUpdate
    }

    enum class Case(
        val days: List<LocalDate>,
        val today: LocalDate,
        val fetchInterval: Int = 0,
        val nextUpdate: Instant? = null,
        val lastUpdate: Instant? = null,
    ) {
        WEEKDAYS(
            everyDays("2019-12-02", "2020-01-20", 1)
                .filter { it.dayOfWeek == DayOfWeek.MONDAY || it.dayOfWeek == DayOfWeek.THURSDAY },
            LocalDate.parse("2020-01-21"),
        ),
        MONTHLY(
            listOf("2019-09-15", "2019-10-15", "2019-11-14", "2019-12-15", "2020-01-16")
                .flatMap { listOf(LocalDate.parse(it), LocalDate.parse(it)) },
            LocalDate.parse("2020-01-20"),
        ),
        HIATUS(everyDays("2019-09-11", "2019-10-02", 7), LocalDate.parse("2020-01-01")),
        DATED_AHEAD(
            everyDays("2019-12-04", "2019-12-25", 7) + LocalDate.parse("2020-06-01"),
            LocalDate.parse("2020-01-01"),
        ),
        KEPT_IN_WINDOW(
            everyDays("2019-12-04", "2019-12-25", 7),
            LocalDate.parse("2020-01-02"),
            nextUpdate = LocalDate.parse("2020-01-01").atStartOfDayIn(ZONE),
        ),
        UNDATED_UPDATED_ON_THE_EXPECTED_DAY(
            emptyList(),
            LocalDate.parse("2020-01-01"),
            nextUpdate = LocalDate.parse("2020-01-01").atStartOfDayIn(ZONE),
            lastUpdate = LocalDate.parse("2020-01-01").atStartOfDayIn(ZONE) + 9.hours,
        ),
        USER_SET(
            emptyList(),
            LocalDate.parse("2020-01-01"),
            fetchInterval = -5,
            lastUpdate = LocalDate.parse("2019-12-20").atStartOfDayIn(ZONE),
        ),
        ;

        val window get() = ReleaseInterval.window(today, ZONE)
    }

    private companion object {
        val ZONE = TimeZone.UTC

        fun noon(day: LocalDate) = day.atStartOfDayIn(ZONE) + 12.hours

        fun everyDays(from: String, to: String, days: Int) =
            generateSequence(LocalDate.parse(from)) { it.plus(DatePeriod(days = days)) }
                .takeWhile { it <= LocalDate.parse(to) }
                .toList()
    }
}
