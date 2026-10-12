package reikai.data.migration

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import java.io.File

/**
 * 63.sqm over novel chapters written in the shape the schema had just before it, reached from the
 * committed 43.db snapshot through the real migrations (database.md).
 */
class RawChapterNumberMigrationTest {

    private lateinit var file: File
    private lateinit var driver: JdbcSqliteDriver

    @BeforeEach
    fun setUp() = runTest {
        file = File.createTempFile("raw-chapter-number", ".db")
        File("src/main/sqldelight/43.db").copyTo(file, overwrite = true)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        DatabaseBindings.providesDatabase(driver).transaction {
            Database.Schema.migrate(driver, 43, BEFORE).await()
        }
        SEED.forEach { driver.execute(null, it, 0).await() }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        file.delete()
    }

    @Test
    fun `a stored chapter takes its stored number as its raw number`() = runTest {
        migrate()

        doubles("SELECT raw_chapter_number FROM novel_chapters ORDER BY _id") shouldBe listOf(5.5, -1.0)
    }

    @Test
    fun `the raw number may be left unknown`() = runTest {
        migrate()

        doubles(
            "SELECT \"notnull\" FROM pragma_table_info('novel_chapters') WHERE name = 'raw_chapter_number'",
        ) shouldBe
            listOf(0.0)
    }

    @Test
    fun `the migration leaves every foreign key satisfied`() = runTest {
        migrate().shouldBeEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["novelHistoryView", "novelLibraryView", "novelUpdatesView", "mergedNovelChapterStaleView"])
    fun `a view over novel chapters still reads`(view: String) = runTest {
        migrate()

        doubles("SELECT count(*) FROM $view").size shouldBe 1
    }

    /** One transaction, as the production driver migrates. Returns what the foreign-key check reports. */
    private suspend fun migrate(): List<Double?> = DatabaseBindings.providesDatabase(driver).transactionWithResult {
        Database.Schema.migrate(driver, BEFORE, Database.Schema.version).await()
        doubles("SELECT rowid FROM pragma_foreign_key_check")
    }

    private suspend fun doubles(sql: String): List<Double?> = driver.executeQuery(
        null,
        sql,
        { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getDouble(0)) }) },
        0,
    ).await()

    companion object {
        /** The schema version 63.sqm migrates from. */
        private const val BEFORE = 63L

        private val SEED = listOf(
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                "VALUES (1, 's', '/n/1', 'n1', 0, 1, 0, 1000)",
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, source_order, " +
                "date_fetch) VALUES (10, 1, '/c/10', 'c', 0, 0, 5.5, 0, 1), (20, 1, '/c/20', 'd', 0, 0, -1, 1, 1)",
        )
    }
}
