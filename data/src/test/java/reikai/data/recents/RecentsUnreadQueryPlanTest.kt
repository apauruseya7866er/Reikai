package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.data.RecordingDriver
import reikai.data.queryPlan
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The caught-up filter reruns on every chapter write, so it may not walk the whole library: no full pass
 * over the chapter rows or the stitch, only the unread chapters through their partial index.
 */
class RecentsUnreadQueryPlanTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["manga", "novels"])
    fun `the caught-up query reads no whole chapter or stitch table`(type: String) = runTest {
        Database.Schema.create(driver).await()
        val issued = mutableListOf<String>()
        val repository = RecentsUnreadRepositoryImpl(DatabaseBindings.providesDatabase(RecordingDriver(driver, issued)))
        when (type) {
            "manga" -> repository.subscribeMangaIdsWithUnread(mergingEnabled = true).first()
            else -> repository.subscribeNovelIdsWithUnread(mergingEnabled = true).first()
        }

        val plan = driver.queryPlan(issued.single())

        withClue(plan.joinToString("\n")) {
            plan.filter { it.matches(Regex("SCAN [CU]\\b.*")) && "unread_by_" !in it }.shouldBeEmpty()
        }
    }
}
