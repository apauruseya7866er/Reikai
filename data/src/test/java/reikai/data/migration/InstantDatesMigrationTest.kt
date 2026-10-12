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
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import java.io.File
import java.util.Properties

/**
 * 62.sqm over rows of both content types, written in the shape the schema had just before it. The
 * real migrations bring the committed 43.db snapshot to that point first, so the shape the rows are
 * written in never changes however many migrations follow (database.md).
 */
class InstantDatesMigrationTest {

    private lateinit var file: File
    private lateinit var driver: JdbcSqliteDriver

    @BeforeEach
    fun setUp() = runTest {
        file = File.createTempFile("instant-dates", ".db")
        File("src/main/sqldelight/43.db").copyTo(file, overwrite = true)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        DatabaseBindings.providesDatabase(driver).transaction {
            Database.Schema.migrate(driver, 43, BEFORE).await()
        }
        SEED.forEach { exec(it) }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        file.delete()
    }

    @ParameterizedTest
    @MethodSource("unknownDates")
    fun `a date stored as 0 reads as unknown`(query: String) = runTest {
        migrate()

        nullableLongs(query) shouldBe listOf(null)
    }

    @ParameterizedTest
    @MethodSource("knownDates")
    fun `a known date keeps its value`(query: String, expected: Long) = runTest {
        migrate()

        nullableLongs(query) shouldBe listOf(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "SELECT user_favorite_at FROM manga WHERE id = 1",
            "SELECT favorite_at FROM novels WHERE _id = 1",
        ],
    )
    fun `an entry added before its date was recorded keeps the unknown date of 0`(query: String) = runTest {
        migrate()

        nullableLongs(query) shouldBe listOf(0L)
    }

    @Test
    fun `the migration leaves every foreign key satisfied`() = runTest {
        migrate().shouldBeEmpty()
    }

    @Test
    fun `nothing in the schema still names a table set aside`() = runTest {
        migrate()

        nullableLongs("SELECT rowid FROM sqlite_master WHERE sql LIKE '%\\_temp%' ESCAPE '\\'").shouldBeEmpty()
    }

    @ParameterizedTest
    @MethodSource("attachedRows")
    fun `removing an entry still removes everything attached to it`(delete: String, attached: String) = runTest {
        migrate()
        // A file database gets a fresh connection per statement, so enforcement is a connection property
        val enforcing = JdbcSqliteDriver(
            "jdbc:sqlite:${file.absolutePath}",
            Properties().apply { put("foreign_keys", "true") },
        )

        enforcing.execute(null, delete, 0).await()

        enforcing.use { nullableLongs(attached) } shouldBe listOf(0L)
    }

    @Test
    fun `a chapter keeps its page count`() = runTest {
        migrate()

        nullableLongs("SELECT page_count FROM chapter WHERE id = 10") shouldBe listOf(12L)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "chapter_remote_url_index", "chapter_state_date_fetch_index", "chapter_unread_state_date_fetch_index",
            "manga_user_favorite_at_index", "manga_track_tracker_id_remote_id_index", "novels_url_index",
            "novels_source_url_index", "novels_library_favorite_at_index", "novel_chapters_novel_id_url_index",
            "novel_chapters_unread_by_novel_index", "novel_chapters_date_fetch_index", "novel_tracks_novel_id_index",
        ],
    )
    fun `a rebuilt table gets its indexes back`(index: String) = runTest {
        migrate()

        nullableLongs("SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = '$index'") shouldBe
            listOf(1L)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "historyView", "libraryView", "updatesView", "library_update_error_view", "mergedChapterStaleView",
            "mergedNovelChapterStaleView", "mergedCountedUnitView", "mergedNovelCountedUnitView",
            "mergedUnitCopiesView", "mergedNovelUnitCopiesView", "novelHistoryView", "novelLibraryView",
            "novelUpdatesView", "novel_update_error_view",
        ],
    )
    fun `a view over a rebuilt table still reads`(view: String) = runTest {
        migrate()

        nullableLongs("SELECT count(*) FROM $view").size shouldBe 1
    }

    /** One transaction, as the production driver migrates. Returns what the foreign-key check reports. */
    private suspend fun migrate(): List<Long?> = DatabaseBindings.providesDatabase(driver).transactionWithResult {
        Database.Schema.migrate(driver, BEFORE, Database.Schema.version).await()
        nullableLongs("SELECT rowid FROM pragma_foreign_key_check")
    }

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private suspend fun nullableLongs(sql: String): List<Long?> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(cursor.getLong(0))
                },
            )
        },
        0,
    ).await()

    companion object {
        /** The schema version 62.sqm migrates from. */
        private const val BEFORE = 62L

        // Entry 1 of each type knows no date and entry 2 knows every one. 1 is in a merge group, has
        // custom info, an update error, a category, a corrected number and, on manga, adult metadata and
        // an excluded scanlator, so each table pointing at a rebuilt one has a row to keep.
        private val SEED = listOf(
            "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, remote_update_strategy, " +
                "remote_memo, user_favorite_at, user_notes, user_reader_flags, user_chapter_flags, " +
                "state_chapter_last_update, state_chapter_next_update, state_chapter_fetch_interval, " +
                "state_cover_last_modified, state_initialized) VALUES " +
                "(1, 1, '/m/1', 'm1', 0, 0, X'7B7D', 0, '', 0, 0, 0, 0, 0, 0, 1), " +
                "(2, 1, '/m/2', 'm2', 0, 0, X'7B7D', 1000, '', 0, 0, 2000, 3000, 7, 4000, 1)",
            "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_chapter_number, remote_date_upload, " +
                "remote_order, remote_memo, user_read, user_bookmark, user_last_page_read, state_date_fetch, " +
                "page_count) VALUES " +
                "(10, 1, '/c/10', 'c', 1, 0, 0, X'7B7D', 1, 0, 0, 0, 12), " +
                "(20, 2, '/c/20', 'c', 1, 5000, 0, X'7B7D', 1, 0, 0, 6000, 0)",
            "INSERT INTO manga_track(manga_id, tracker_id, remote_id, remote_url, title, last_chapter_read, " +
                "total_chapters, status, score, start_date, finish_date) VALUES " +
                "(1, 1, 7, 'u', 't', 1, 0, 0, 0, 0, 0), (2, 1, 8, 'u', 't', 1, 0, 0, 0, 7000, 8000)",
            "INSERT INTO history(manga_id, chapter_id, read_at, read_duration) VALUES (1, 10, 0, 60), (2, 20, 9000, 60)",
            "INSERT INTO custom_manga_info(manga_id, title) VALUES (1, 'custom')",
            "INSERT INTO search_metadata(manga_id, extra, extra_version) VALUES (1, '{}', 1)",
            "INSERT INTO library_update_errors(manga_id, message) VALUES (1, 'failed')",
            "INSERT INTO excluded_scanlator(manga_id, scanlator) VALUES (1, 'x')",
            "INSERT INTO manga_category(manga_id, category_id) VALUES (1, 0)",
            "INSERT INTO manga_chapter_number_override(manga_id, url, number, source_number) VALUES (1, '/c/10', 2, 1)",
            "INSERT INTO merge_group(_id, content_type) VALUES (1, 0), (2, 1)",
            "INSERT INTO merge_group_manga(group_id, manga_id) VALUES (1, 1)",
            "INSERT INTO merged_chapter_unit(chapter_id, group_id, unit, copy_order, derived_number) " +
                "VALUES (10, 1, 0, 0, 1)",
            "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at, " +
                "last_update, cover_last_modified, next_update) VALUES " +
                "(1, 's', '/n/1', 'n1', 0, 1, 0, 0, 0, 0, 0), (2, 's', '/n/2', 'n2', 0, 1, 0, 1000, 2000, 4000, 3000)",
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, source_order, " +
                "date_fetch, date_upload) VALUES " +
                "(10, 1, '/c/10', 'c', 1, 0, 1, 0, 0, 0), (20, 2, '/c/20', 'c', 1, 0, 1, 0, 6000, 5000)",
            "INSERT INTO novel_tracks(novel_id, sync_id, remote_id, title, last_chapter_read, total_chapters, " +
                "status, score, remote_url, start_date, finish_date) VALUES " +
                "(1, 1, 7, 't', 1, 0, 0, 0, 'u', 0, 0), (2, 1, 8, 't', 1, 0, 0, 0, 'u', 7000, 8000)",
            "INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES (10, 0, 60), (20, 9000, 60)",
            "INSERT INTO custom_novel_info(novel_id, title) VALUES (1, 'custom')",
            "INSERT INTO novel_update_errors(novel_id, message) VALUES (1, 'failed')",
            "INSERT INTO novels_categories(novel_id, category_id) VALUES (1, 0)",
            "INSERT INTO novel_chapter_number_override(novel_id, url, number, source_number) VALUES (1, '/c/10', 2, 1)",
            "INSERT INTO merge_group_novel(group_id, novel_id) VALUES (2, 1)",
            "INSERT INTO merged_novel_chapter_unit(chapter_id, group_id, unit, copy_order, derived_name, " +
                "derived_number) VALUES (10, 2, 0, 0, 'c', 1)",
        )

        /** Each type's delete of entry 1, and the count of every row seeded against that entry. */
        @JvmStatic
        fun attachedRows() = listOf(
            arrayOf(
                "DELETE FROM manga WHERE id = 1",
                countOf(
                    "chapter WHERE manga_id = 1", "manga_track WHERE manga_id = 1", "history WHERE manga_id = 1",
                    "custom_manga_info WHERE manga_id = 1", "search_metadata WHERE manga_id = 1",
                    "library_update_errors WHERE manga_id = 1", "excluded_scanlator WHERE manga_id = 1",
                    "manga_category WHERE manga_id = 1", "manga_chapter_number_override WHERE manga_id = 1",
                    "merge_group_manga WHERE manga_id = 1", "merged_chapter_unit WHERE chapter_id = 10",
                ),
            ),
            arrayOf(
                "DELETE FROM novels WHERE _id = 1",
                countOf(
                    "novel_chapters WHERE novel_id = 1", "novel_tracks WHERE novel_id = 1",
                    "novel_history WHERE chapter_id = 10", "custom_novel_info WHERE novel_id = 1",
                    "novel_update_errors WHERE novel_id = 1", "novels_categories WHERE novel_id = 1",
                    "novel_chapter_number_override WHERE novel_id = 1", "merge_group_novel WHERE novel_id = 1",
                    "merged_novel_chapter_unit WHERE chapter_id = 10",
                ),
            ),
        )

        private fun countOf(vararg rows: String) =
            rows.joinToString(" + ", prefix = "SELECT ") { "(SELECT count(*) FROM $it)" }

        @JvmStatic
        fun unknownDates() = listOf(
            "SELECT state_chapter_last_update FROM manga WHERE id = 1",
            "SELECT state_chapter_next_update FROM manga WHERE id = 1",
            "SELECT state_cover_last_modified FROM manga WHERE id = 1",
            "SELECT remote_date_upload FROM chapter WHERE id = 10",
            "SELECT start_date FROM manga_track WHERE manga_id = 1",
            "SELECT finish_date FROM manga_track WHERE manga_id = 1",
            "SELECT read_at FROM history WHERE chapter_id = 10",
            "SELECT latestUpload FROM libraryView WHERE id = 1",
            "SELECT lastRead FROM libraryView WHERE id = 1",
            "SELECT last_update FROM novels WHERE _id = 1",
            "SELECT next_update FROM novels WHERE _id = 1",
            "SELECT cover_last_modified FROM novels WHERE _id = 1",
            "SELECT date_upload FROM novel_chapters WHERE _id = 10",
            "SELECT start_date FROM novel_tracks WHERE novel_id = 1",
            "SELECT finish_date FROM novel_tracks WHERE novel_id = 1",
            "SELECT last_read FROM novel_history WHERE chapter_id = 10",
            "SELECT latestUpload FROM novelLibraryView WHERE _id = 1",
            "SELECT lastRead FROM novelLibraryView WHERE _id = 1",
        )

        @JvmStatic
        fun knownDates() = listOf(
            arrayOf<Any>("SELECT state_chapter_last_update FROM manga WHERE id = 2", 2000L),
            arrayOf<Any>("SELECT state_chapter_next_update FROM manga WHERE id = 2", 3000L),
            arrayOf<Any>("SELECT state_cover_last_modified FROM manga WHERE id = 2", 4000L),
            arrayOf<Any>("SELECT remote_date_upload FROM chapter WHERE id = 20", 5000L),
            arrayOf<Any>("SELECT state_date_fetch FROM chapter WHERE id = 20", 6000L),
            arrayOf<Any>("SELECT state_date_fetch FROM chapter WHERE id = 10", 0L),
            arrayOf<Any>("SELECT start_date FROM manga_track WHERE manga_id = 2", 7000L),
            arrayOf<Any>("SELECT finish_date FROM manga_track WHERE manga_id = 2", 8000L),
            arrayOf<Any>("SELECT read_at FROM history WHERE chapter_id = 20", 9000L),
            arrayOf<Any>("SELECT user_favorite_at FROM manga WHERE id = 2", 1000L),
            arrayOf<Any>("SELECT last_update FROM novels WHERE _id = 2", 2000L),
            arrayOf<Any>("SELECT next_update FROM novels WHERE _id = 2", 3000L),
            arrayOf<Any>("SELECT cover_last_modified FROM novels WHERE _id = 2", 4000L),
            arrayOf<Any>("SELECT date_upload FROM novel_chapters WHERE _id = 20", 5000L),
            arrayOf<Any>("SELECT date_fetch FROM novel_chapters WHERE _id = 20", 6000L),
            arrayOf<Any>("SELECT date_fetch FROM novel_chapters WHERE _id = 10", 0L),
            arrayOf<Any>("SELECT start_date FROM novel_tracks WHERE novel_id = 2", 7000L),
            arrayOf<Any>("SELECT finish_date FROM novel_tracks WHERE novel_id = 2", 8000L),
            arrayOf<Any>("SELECT last_read FROM novel_history WHERE chapter_id = 20", 9000L),
            arrayOf<Any>("SELECT favorite_at FROM novels WHERE _id = 2", 1000L),
        )
    }
}
