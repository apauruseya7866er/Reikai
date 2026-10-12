package mihon.core.migration.migrations

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.db.SqlDelightTransactions
import reikai.domain.category.CategoryContentType
import reikai.domain.novel.NovelPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.Novels
import tachiyomi.data.category.CategoryRepositoryImpl

/**
 * The migrator stamps its version only after the whole chain resolves, so a kill later in the chain
 * runs this again. The flag translation is a swap, so a second run must change nothing, and the id
 * shift's ceiling keeps a run cut short from shifting an id twice. Only the moved novel rows carry the
 * novel flag layout.
 */
class MigrateNovelCategoriesToSharedTableMigrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var categories: CategoryRepositoryImpl
    private lateinit var migration: MigrateNovelCategoriesToSharedTableMigration
    private val store = EmittingPreferenceStore()
    private val novelPreferences = NovelPreferences(store)

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            database.categoryQueries.insert(name = "Novels", flags = NOVEL_DOWNLOADED, contentType = 2L)
            database.categoryQueries.insert(name = "Manga", flags = NOVEL_DOWNLOADED, contentType = 1L)
            novelPreferences.defaultNovelCategory().set(5)
            categories = CategoryRepositoryImpl(database)
            migration = MigrateNovelCategoriesToSharedTableMigration(
                categories,
                SqlDelightTransactions(database),
                novelPreferences,
                store,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun runTwice() {
        repeat(2) { migration.invoke(MigrationContext(dryrun = false, previousVersion = 185)) }
    }

    private suspend fun flagsOf(contentType: Long) =
        categories.getUnfiltered().single { it.contentType == contentType }.flags

    @Test
    fun `a second run leaves the translated sort flags alone`() = runTest {
        runTwice()

        flagsOf(CategoryContentType.NOVEL) shouldBe MANGA_DOWNLOADED
    }

    @Test
    fun `a manga category keeps its sort flags`() = runTest {
        migration.invoke(MigrationContext(dryrun = false, previousVersion = 185))

        flagsOf(CategoryContentType.MANGA) shouldBe NOVEL_DOWNLOADED
    }

    @Test
    fun `a second run leaves the shifted category ids alone`() = runTest {
        runTwice()

        novelPreferences.defaultNovelCategory().get() shouldBe 5 + OFFSET.toInt()
    }

    @Test
    fun `every category-set preference shifts to the moved ids`() = runTest {
        setPrefs().forEach { it.set(setOf("3", "0")) }

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 185))

        setPrefs().map { it.get() } shouldBe List(5) { setOf("${3 + OFFSET}", "0") }
    }

    @Test
    fun `an id a run cut short already shifted is not shifted again`() = runTest {
        // The marker is only set once every preference has shifted, so a rerun meets some already moved.
        novelPreferences.defaultNovelCategory().set(5 + OFFSET.toInt())
        novelPreferences.novelUpdateCategories().set(setOf("3", "${7 + OFFSET}"))

        migration.invoke(MigrationContext(dryrun = false, previousVersion = 185))

        (novelPreferences.defaultNovelCategory().get() to novelPreferences.novelUpdateCategories().get()) shouldBe
            ((5 + OFFSET.toInt()) to setOf("${3 + OFFSET}", "${7 + OFFSET}"))
    }

    private fun setPrefs() = listOf(
        novelPreferences.removeExcludeCategories(),
        novelPreferences.downloadNewChapterCategories(),
        novelPreferences.downloadNewChapterCategoriesExclude(),
        novelPreferences.novelUpdateCategories(),
        novelPreferences.novelUpdateCategoriesExclude(),
    )

    private companion object {
        // The novel layout's Downloaded sort type, and the manga layout's.
        const val NOVEL_DOWNLOADED = 0b100000L
        const val MANGA_DOWNLOADED = 0b100100L
        const val OFFSET = MigrateNovelCategoriesToSharedTableMigration.NOVEL_CATEGORY_ID_MIGRATION_OFFSET
    }
}
