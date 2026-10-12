package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.chapter.model.ChapterSummary

/** The migration list counts a novel's chapters in the database, over the real SQL. */
class NovelChapterSummaryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: NovelChapterRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            repository = NovelChapterRepositoryImpl(DatabaseBindings.providesDatabase(driver))
            listOf(1, 2).forEach { id ->
                driver.execute(
                    null,
                    "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                        "favorite_at) VALUES ($id, 'src', 'n-url-$id', 'title', 0, 0, 0, 0)",
                    0,
                ).await()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun chapter(novelId: Long, number: Double) {
        driver.execute(
            null,
            "INSERT INTO novel_chapters(novel_id, url, name, read, bookmark, last_text_progress, chapter_number, " +
                "source_order, date_fetch, date_upload, page) " +
                "VALUES ($novelId, 'c-$novelId-$number', 'c', 0, 0, 0, $number, 0, 0, 0, '')",
            0,
        ).await()
    }

    @Test
    fun `a novel's summary counts only its own chapters and takes the highest number`() = runTest {
        chapter(1L, 3.0)
        chapter(1L, 12.5)
        chapter(1L, -1.0)
        chapter(2L, 40.0)

        repository.getChapterSummaryByNovelId(1L) shouldBe ChapterSummary(chapterCount = 3, latestChapterNumber = 12.5)
    }

    @Test
    fun `a novel with no chapters counts none and has no highest number`() = runTest {
        repository.getChapterSummaryByNovelId(1L) shouldBe ChapterSummary(chapterCount = 0, latestChapterNumber = null)
    }
}
