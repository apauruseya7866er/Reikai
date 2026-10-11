package mihon.data.extension.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * Refreshing fetches every store first and then writes them together (mihon 8066b8dfc), and a store removed
 * while its index is still being fetched stays removed (mihon fc5592ff7). The service is the network
 * boundary, so it is the one mock.
 */
class ExtensionStoreRefreshTest {

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

    @Test
    fun `a store removed while its refresh is in flight stays removed`() = runTest {
        val service = mockk<ExtensionStoreService> {
            coEvery { fetch(URL) } coAnswers {
                database.extension_storeQueries.delete(URL)
                Result.success(store(URL, "Store"))
            }
        }
        val repository = ExtensionStoreRepositoryImpl(backgroundScope, service, database)
        repository.insertFromPreference(URL, "Store")

        repository.refreshAll()

        database.extension_storeQueries.getAll().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `every store is fetched before any of them is written`() = runTest {
        val namesSeenWhileFetching = mutableSetOf<String>()
        val service = mockk<ExtensionStoreService> {
            coEvery { fetch(any()) } coAnswers {
                namesSeenWhileFetching += database.extension_storeQueries.getAll().awaitAsList().map { it.name }
                Result.success(store(firstArg(), "Renamed"))
            }
        }
        val repository = ExtensionStoreRepositoryImpl(backgroundScope, service, database)
        repository.insertFromPreference(URL, "Store")
        repository.insertFromPreference(OTHER_URL, "Other")

        repository.refreshAll()

        namesSeenWhileFetching shouldBe setOf("Store", "Other")
    }

    @Test
    fun `a store whose fetch fails keeps its row while the others refresh`() = runTest {
        val service = mockk<ExtensionStoreService> {
            coEvery { fetch(URL) } returns Result.failure(IllegalStateException("offline"))
            coEvery { fetch(OTHER_URL) } returns Result.success(store(OTHER_URL, "Renamed"))
        }
        val repository = ExtensionStoreRepositoryImpl(backgroundScope, service, database)
        repository.insertFromPreference(URL, "Store")
        repository.insertFromPreference(OTHER_URL, "Other")

        repository.refreshAll()

        listOf(nameOf(URL), nameOf(OTHER_URL)) shouldBe listOf("Store", "Renamed")
    }

    private suspend fun nameOf(indexUrl: String): String? =
        database.extension_storeQueries.getAll().awaitAsList().firstOrNull { it.index_url == indexUrl }?.name

    private companion object {
        const val URL = "https://example.org/index.min.json"
        const val OTHER_URL = "https://example.com/index.min.json"

        fun store(indexUrl: String, name: String) = ExtensionStore(
            indexUrl = indexUrl,
            name = name,
            badgeLabel = name,
            signingKey = "key",
            contact = ExtensionStore.Contact(website = "https://example.org", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
    }
}
