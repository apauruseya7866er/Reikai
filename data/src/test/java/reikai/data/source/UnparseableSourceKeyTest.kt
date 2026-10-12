package reikai.data.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.source.SourceKey
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * Both tables keyed on a serialized [SourceKey] drop a row whose key no longer parses and keep the rest.
 * Each case seeds a good row beside the broken one: a list holding only the broken row cannot tell a
 * dropped row apart from the whole read failing.
 */
class UnparseableSourceKeyTest {

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
    @EnumSource(Table::class)
    fun `a row whose stored source no longer parses is left out`(table: Table) = runTest {
        table.insertGood(database)
        driver.execute(null, table.brokenRow, 0).await()

        table.readSources(database) shouldBe listOf(GOOD)
    }

    enum class Table(val brokenRow: String) {
        SAVED_SEARCH(
            "INSERT INTO saved_search(source_key, name, query, filters_json) VALUES ('not a source key', 'b', NULL, NULL)",
        ) {
            override suspend fun insertGood(database: Database) {
                SavedSearchRepositoryImpl(database).insert(GOOD, "Fine", null, null)
            }

            override suspend fun readSources(database: Database) =
                SavedSearchRepositoryImpl(database).getAll().map { it.sourceKey }
        },
        FEED(
            "INSERT INTO feed_saved_search(source_key, saved_search, global, feed_order) " +
                "VALUES ('not a source key', NULL, 1, 9)",
        ) {
            override suspend fun insertGood(database: Database) {
                FeedSavedSearchRepositoryImpl(database).insert(GOOD, savedSearchId = null, global = true)
            }

            override suspend fun readSources(database: Database) =
                FeedSavedSearchRepositoryImpl(database).getAll().map { it.sourceKey }
        },
        ;

        abstract suspend fun insertGood(database: Database)

        abstract suspend fun readSources(database: Database): List<SourceKey>
    }

    private companion object {
        val GOOD = SourceKey.Manga(9001L)
    }
}
