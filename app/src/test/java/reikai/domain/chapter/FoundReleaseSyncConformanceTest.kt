package reikai.domain.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.chapter.ChapterNumberOverrideRepositoryImpl
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.syncChaptersWithNovelSource
import reikai.novel.host.ChapterItem
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * Whether a sync found a release, which the next update is counted from, through each type's real sync
 * over a real database. Both reach the rule through [foundRelease].
 */
class FoundReleaseSyncConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        database = DatabaseBindings.providesDatabase(driver)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a first fetch is the back catalogue, not a release`(type: Type) = runTest {
        type.seed(driver)

        type.sync(database, Listed("/c1", 1.0), Listed("/c2", 2.0)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new chapter on a stored entry is a release`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, Listed("/c1", 1.0))

        type.sync(database, Listed("/c2", 2.0), Listed("/c1", 1.0)) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter re-listed under a removed number is not a release`(type: Type) = runTest {
        type.seed(driver)
        type.sync(database, Listed("/c2", 2.0), Listed("/c1", 1.0))

        type.sync(database, Listed("/c2-new", 2.0), Listed("/c1", 1.0)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new chapter only from an excluded group is not a release`(type: Type) = runTest {
        assumeTrue(type.excludesGroups, "novels have no excluded groups")
        type.seed(driver)
        type.sync(database, Listed("/c1", 1.0))

        type.sync(database, Listed("/c2", 2.0, EXCLUDED), Listed("/c1", 1.0)) shouldBe false
    }

    data class Listed(val url: String, val number: Double, val scanlator: String? = null)

    enum class Type(val excludesGroups: Boolean) {
        MANGA(excludesGroups = true) {
            override suspend fun seed(driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                        "user_reader_flags, user_chapter_flags, user_favorite_at, remote_update_strategy, " +
                        "state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                        "($OWNER, 1, 'u', 'T', 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                    0,
                ).await()
            }

            override suspend fun sync(database: Database, vararg chapters: Listed): Boolean {
                val found = slot<Boolean>()
                val updateManga = mockk<UpdateManga> {
                    coEvery {
                        awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any(), any(), capture(found))
                    } returns
                        true
                }
                val excluded = mockk<GetExcludedScanlators> { coEvery { await(OWNER) } returns setOf(EXCLUDED) }
                SyncChaptersWithSource(
                    downloadManager = mockk(relaxed = true),
                    downloadProvider = mockk(relaxed = true),
                    chapterRepository = ChapterRepositoryImpl(database),
                    shouldUpdateDbChapter = ShouldUpdateDbChapter(),
                    updateManga = updateManga,
                    getExcludedScanlators = excluded,
                    libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
                    chapterNumberOverrides = ChapterNumberOverrideRepositoryImpl(database),
                ).await(
                    chapters.map { listed ->
                        SChapter.create().apply {
                            url = listed.url
                            name = "Chapter ${listed.number}"
                            chapter_number = listed.number.toFloat()
                            scanlator = listed.scanlator
                        }
                    },
                    Manga.create().copy(id = OWNER, title = "T", source = 1),
                    mockk<Source> { every { id } returns 1L },
                )
                return found.captured
            }
        },
        NOVEL(excludesGroups = false) {
            override suspend fun seed(driver: JdbcSqliteDriver) {
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                        "VALUES ($OWNER, 'src', 'u', 'T', 0, 0, 0, 0)",
                    0,
                ).await()
            }

            override suspend fun sync(database: Database, vararg chapters: Listed): Boolean =
                syncChaptersWithNovelSource(
                    chapters.map {
                        ChapterItem(name = "Chapter ${it.number}", path = it.url, chapterNumber = it.number)
                    },
                    NovelRepositoryImpl(database).getById(OWNER)!!,
                    NovelChapterRepositoryImpl(database),
                    NovelRepositoryImpl(database),
                    LibraryPreferences(InMemoryPreferenceStore()),
                    ChapterNumberOverrideRepositoryImpl(database),
                ).foundRelease
        },
        ;

        abstract suspend fun seed(driver: JdbcSqliteDriver)

        /** Syncs a source listing [chapters], newest first, and returns whether the sync found a release. */
        abstract suspend fun sync(database: Database, vararg chapters: Listed): Boolean
    }

    private companion object {
        const val OWNER = 1L
        const val EXCLUDED = "Excluded group"
    }
}
