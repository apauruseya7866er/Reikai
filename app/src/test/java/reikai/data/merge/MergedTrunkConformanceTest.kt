package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaGroupStitcher
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.MergedGroupStitcher
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelGroupStitcher
import reikai.domain.novel.NovelMergeManager
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.MangaMergeCollapse
import reikai.presentation.library.novels.NovelMergeCollapse
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.Novels
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.source.service.SourceManager

/**
 * The collapsed library row leads on the member the stitch makes the trunk, for both content types:
 * its cover, title and badge are that member's, and the details list opens on the stitch. Each type
 * ranks through the same source-priority kernel, then a chapter count, and the library has to read
 * the count the stitch ranks on. Runs the library's own rows and counts from real SQL, with no stitch
 * stored, which is how every group starts.
 */
class MergedTrunkConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var groups: MergeGroupRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = DatabaseBindings.providesDatabase(driver)
            groups = MergeGroupRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `with no ranking set the library leads on the stitch's trunk`(type: ContentType) = runTest {
        val side = side(type)
        // The larger member lists most of its chapters under a scanlator the user hides, which is a
        // display choice the stitch does not see. Novels offer no scanlator filter, so theirs are all shown.
        side.entry(id = LARGER, source = 100L, chapters = 10, hidden = 8)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        side.libraryPrimary(preferred = emptyList()) shouldBe side.storedLead(preferred = emptyList())
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a preferred source leads the library as it leads the stitch`(type: ContentType) = runTest {
        val side = side(type)
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))

        side.libraryPrimary(preferred = listOf(200L)) shouldBe side.storedLead(preferred = listOf(200L))
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a deletion that flips the chapter counts moves the library and the stored stitch together`(
        type: ContentType,
    ) = runTest {
        val side = side(type)
        side.entry(id = LARGER, source = 100L, chapters = 10)
        side.entry(id = SMALLER, source = 200L, chapters = 5)
        groups.createGroup(type, listOf(LARGER, SMALLER))
        side.storedLead(preferred = emptyList())

        exec("DELETE FROM ${side.chapterTable} WHERE ${side.ownerColumn} = $LARGER AND ${side.numberColumn} > 2")

        side.libraryPrimary(preferred = emptyList()) shouldBe side.storedLead(preferred = emptyList())
    }

    private fun side(type: ContentType): Side = if (type == ContentType.NOVELS) NovelSide() else MangaSide()

    private interface Side {
        val chapterTable: String

        val ownerColumn: String

        val numberColumn: String

        suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int = 0)

        suspend fun libraryPrimary(preferred: List<Long>): Long

        /** Reconciles the stored stitch through the real stitcher, then names the member whose copy it
         *  shows first. */
        suspend fun storedLead(preferred: List<Long>): Long
    }

    private suspend fun storedLead(stitcher: MergedGroupStitcher, ownerOf: suspend (Long) -> Long): Long {
        val units = MergedChapterUnitRepositoryImpl(database)
        ReconcileMergedChapters(units, setOf(stitcher)).await()
        val group = groups.getAllMemberships(stitcher.contentType).values.distinct().single()
        return ownerOf(
            units.getStitch(stitcher.contentType, group).first {
                it.unit == 0 && it.copyOrder == 0
            }.chapterId,
        )
    }

    private val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())

    /** The library preferences with [preferred] first in a source ranking stored under [key]. */
    private fun preferring(key: String, preferred: List<Any>) = ReikaiLibraryPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference(key, preferred, emptyList<Any>()))),
    )

    private inner class MangaSide : Side {
        override val chapterTable = "chapter"

        override val ownerColumn = "manga_id"

        override val numberColumn = "remote_chapter_number"

        override suspend fun storedLead(preferred: List<Long>): Long {
            val chapters = ChapterRepositoryImpl(database)
            val sourceManager = mockk<SourceManager> { coEvery { get(any<Long>()) } returns null }
            val stitcher = MangaGroupStitcher(
                groups,
                GetMangaWithChapters(MangaRepositoryImpl(database), chapters),
                MangaMergeManager(groups, preferences, onMerged = {}) {},
                sourceManager,
                preferring("preferred_manga_sources", preferred),
            )
            return storedLead(stitcher) { chapters.getChapterById(it)!!.mangaId }
        }

        override suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int) {
            exec(
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, $source, " +
                    "'m$id', 'title', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
            )
            (1..chapters).forEach { number ->
                val scanlator = if (number <= hidden) "'hidden'" else "NULL"
                exec(
                    "INSERT INTO chapter(manga_id, remote_url, remote_name, remote_scanlator, " +
                        "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                        "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES ($id, " +
                        "'/$id/$number', 'Chapter $number', $scanlator, 0, 0, 0, $number, $number, 0, 0, " +
                        "'{}')",
                )
            }
            if (hidden > 0) exec("INSERT INTO excluded_scanlator(manga_id, scanlator) VALUES ($id, 'hidden')")
        }

        override suspend fun libraryPrimary(preferred: List<Long>): Long {
            val items = MangaRepositoryImpl(database).getLibraryManga().map { row ->
                LibraryItem(
                    libraryManga = row,
                    downloadCount = 0,
                    unreadCount = 0,
                    isLocal = false,
                    badges = LibraryItem.Badges(
                        downloadCount = 0,
                        unreadCount = 0,
                        isLocal = false,
                        sourceLanguage = "",
                    ),
                )
            }
            return MangaMergeCollapse.collapse(
                items = items,
                badgePrefs = LibraryBadgePrefs(false, false, false, false, false),
                membership = groups.getAllMemberships(ContentType.MANGA),
                mergingEnabled = true,
                showMergeSourceIcons = false,
                resolveSource = { error("source icons are off") },
                preferredSourceIds = preferred,
                recognizedChapterCounts = MergedChapterUnitRepositoryImpl(database)
                    .getRecognizedChapterCountsAsFlow().first(),
            ).single().libraryManga.manga.id
        }
    }

    private inner class NovelSide : Side {
        override val chapterTable = "novel_chapters"

        override val ownerColumn = "novel_id"

        override val numberColumn = "chapter_number"

        override suspend fun storedLead(preferred: List<Long>): Long {
            val chapters = NovelChapterRepositoryImpl(database)
            val stitcher = NovelGroupStitcher(
                groups,
                NovelRepositoryImpl(database),
                chapters,
                NovelMergeManager(groups, preferences, onMerged = {}) {},
                preferring("preferred_novel_sources", preferred.map(Long::toString)),
            )
            return storedLead(stitcher) { chapters.getById(it)!!.novelId }
        }

        override suspend fun entry(id: Long, source: Long, chapters: Int, hidden: Int) {
            exec(
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES ($id, '$source', 'n$id', 'title', 0, 0, 0, 0)",
            )
            (1..chapters).forEach { number ->
                exec(
                    "INSERT INTO novel_chapters(novel_id, url, name, read, bookmark, chapter_number, " +
                        "source_order, date_fetch, date_upload) " +
                        "VALUES ($id, '/$id/$number', 'Chapter $number', 0, 0, $number, $number, 0, 0)",
                )
            }
        }

        override suspend fun libraryPrimary(preferred: List<Long>): Long =
            NovelMergeCollapse.collapse(
                library = NovelRepositoryImpl(database).getLibraryNovelAsFlow().first(),
                membership = groups.getAllMemberships(ContentType.NOVELS),
                mergingEnabled = true,
                preferredSourceIds = preferred.map(Long::toString),
            ).single().representative.novel.id
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private companion object {
        // The larger member has the higher id, so the id tiebreak alone would pick the wrong one.
        const val LARGER = 2L
        const val SMALLER = 1L
    }
}
