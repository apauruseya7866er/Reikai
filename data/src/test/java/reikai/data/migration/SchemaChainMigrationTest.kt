package reikai.data.migration

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.dedupe.MergedDuplicateRepositoryImpl
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import java.io.File

/**
 * The real migrations run over rows written into the committed 43.db snapshot, the schema before
 * 43.sqm. The snapshot never changes, so these rows stay valid however many migrations follow, which a
 * hand-built older schema would not (database.md). Manga duplicates merge in 50.sqm and novel ones in
 * 51.sqm by one rule, so each case runs over both.
 */
class SchemaChainMigrationTest {

    private lateinit var file: File
    private lateinit var driver: JdbcSqliteDriver

    @BeforeEach
    fun setUp() {
        file = File.createTempFile("schema-chain", ".db")
        File("src/main/sqldelight/43.db").copyTo(file, overwrite = true)
        driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        file.delete()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `copies of an entry merge into the one in the library`(type: Type) = runTest {
        exec(type.entry(id = 1, url = "/g/1", favorite = false))
        exec(type.entry(id = 2, url = "/g/1", favorite = true))
        exec(type.entry(id = 3, url = "/g/1", favorite = false))

        migrate()

        longs(type.entryIds) shouldBe listOf(2L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `each merged-away copy is recorded against the entry it merged into`(type: Type) = runTest {
        exec(type.entry(id = 1, url = "/g/1", favorite = false))
        exec(type.entry(id = 2, url = "/g/1", favorite = true))
        exec(type.entry(id = 3, url = "/g/1", favorite = false))
        exec(type.entry(id = 4, url = "/g/4", favorite = true))

        migrate()

        MergedDuplicateRepositoryImpl(DatabaseBindings.providesDatabase(driver)).getAll() shouldContainExactlyInAnyOrder
            listOf(
                MergedDuplicate(type.domainType, 1, 2, type.title(1)),
                MergedDuplicate(type.domainType, 3, 2, type.title(3)),
            )
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `each merged-away chapter is recorded against the kept one with the same url`(type: Type) = runTest {
        exec(type.entry(id = 1, url = "/g/1", favorite = false))
        exec(type.entry(id = 2, url = "/g/1", favorite = true))
        exec(type.chapter(id = 10, entryId = 1, url = "/c/1", read = true))
        exec(type.chapter(id = 20, entryId = 2, url = "/c/1", read = false))
        exec(type.chapter(id = 30, entryId = 1, url = "/c/3", read = false))

        migrate()

        MergedDuplicateRepositoryImpl(DatabaseBindings.providesDatabase(driver)).getChapters() shouldBe
            listOf(MergedDuplicateChapter(type.domainType, 20, 10))
    }

    @Test
    fun `emptying the record empties its chapters too`() = runTest {
        exec(Type.NOVEL.entry(id = 1, url = "/g/1", favorite = false))
        exec(Type.NOVEL.entry(id = 2, url = "/g/1", favorite = true))
        exec(Type.NOVEL.chapter(id = 10, entryId = 1, url = "/c/1", read = true))
        exec(Type.NOVEL.chapter(id = 20, entryId = 2, url = "/c/1", read = false))
        migrate()
        val record = MergedDuplicateRepositoryImpl(DatabaseBindings.providesDatabase(driver))

        record.clear()

        record.getChapters().shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter read on a merged copy stays read`(type: Type) = runTest {
        exec(type.entry(id = 1, url = "/g/1", favorite = false))
        exec(type.entry(id = 2, url = "/g/1", favorite = true))
        exec(type.chapter(id = 10, entryId = 1, url = "/c/1", read = true))
        exec(type.chapter(id = 20, entryId = 2, url = "/c/1", read = false))

        migrate()

        longs(type.readOfEntry2) shouldBe listOf(1L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `history on a merged copy follows its chapter to the kept entry`(type: Type) = runTest {
        exec(type.entry(id = 1, url = "/g/1", favorite = false))
        exec(type.entry(id = 2, url = "/g/1", favorite = true))
        exec(type.chapter(id = 10, entryId = 1, url = "/c/1", read = true))
        exec("INSERT INTO ${type.historyTable}(chapter_id, last_read, time_read) VALUES (10, 500, 60)")

        migrate()

        longs(type.historyOwners) shouldBe listOf(2L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `merging leaves no row pointing at a removed entry`(type: Type) = runTest {
        exec(type.entry(id = 1, url = "/g/1", favorite = false))
        exec(type.entry(id = 2, url = "/g/1", favorite = true))
        exec(type.chapter(id = 10, entryId = 1, url = "/c/1", read = true))
        exec("INSERT INTO categories(_id, name, sort, flags, content_type) VALUES (5, 'A', 1, 0, 0)")
        exec("INSERT INTO ${type.categoryTable}(${type.ownerColumn}, category_id) VALUES (1, 5)")
        exec(
            "INSERT INTO ${type.trackTable}(${type.ownerColumn}, sync_id, remote_id, title, last_chapter_read, " +
                "total_chapters, status, score, remote_url, start_date, finish_date) " +
                "VALUES (1, 1, 7, 't', 1, 0, 0, 0, 'u', 0, 0)",
        )

        migrate().shouldBeEmpty()
    }

    // Novel only: migration 65 clears every manga stitch, so the chain end cannot show a manga one kept.
    @Test
    fun `a novel merge group the dedupe did not touch keeps its stitch`() = runTest {
        stitchedGroup(Type.NOVEL)

        migrate()

        longs("SELECT chapter_id FROM ${Type.NOVEL.unitTable}") shouldBe listOf(10L)
    }

    @Test
    fun `every manga stitch is cleared so its group restitches and saves its member counts`() = runTest {
        stitchedGroup(Type.MANGA)

        migrate()

        longs("SELECT chapter_id FROM ${Type.MANGA.unitTable}").shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a stitch stored before the walk order was recorded reads as stale`(type: Type) = runTest {
        stitchedGroup(type)

        migrate()

        longs("SELECT DISTINCT groupId FROM ${type.staleView}") shouldBe listOf(1L)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an entry filed twice under one category is filed once`(type: Type) = runTest {
        exec(type.entry(id = 1, url = "/g/1", favorite = true))
        exec("INSERT INTO categories(_id, name, sort, flags, content_type) VALUES (5, 'A', 1, 0, 0)")
        exec("INSERT INTO ${type.categoryTable}(${type.ownerColumn}, category_id) VALUES (1, 5), (1, 5)")

        migrate()

        longs("SELECT count(*) FROM ${type.categoryTableNow}") shouldBe listOf(1L)
    }

    @Test
    fun `a novel's Last read stamp becomes one history row on its latest-read chapter`() = runTest {
        // Novel 1: progress decides. 3: read beats number. 4: number decides. 2: cleared history, kept
        // cleared. 5: no chapters, nothing to carry. 6: no stamp.
        (1L..6L).forEach { exec(Type.NOVEL.entry(id = it, url = "/n/$it", favorite = true)) }
        listOf(1L to 1001, 2L to 1002, 3L to 1003, 4L to 1004, 5L to 1005).forEach { (id, stamp) ->
            exec("UPDATE novels SET last_read_at = $stamp WHERE _id = $id")
        }
        listOf(
            novelChapter(id = 10, novelId = 1, read = true, progress = 0, number = 9),
            novelChapter(id = 11, novelId = 1, read = false, progress = 40, number = 2),
            novelChapter(id = 20, novelId = 2, read = true, progress = 0, number = 1),
            novelChapter(id = 30, novelId = 3, read = false, progress = 0, number = 9),
            novelChapter(id = 31, novelId = 3, read = true, progress = 0, number = 2),
            novelChapter(id = 40, novelId = 4, read = true, progress = 0, number = 2),
            novelChapter(id = 41, novelId = 4, read = true, progress = 0, number = 7),
            novelChapter(id = 60, novelId = 6, read = true, progress = 0, number = 1),
        ).forEach { exec(it) }
        exec("INSERT INTO novel_history(chapter_id, last_read, time_read) VALUES (20, 0, 50)")

        migrate()

        pairs("SELECT chapter_id, last_read FROM novel_history ORDER BY chapter_id") shouldBe listOf(
            11L to 1001L,
            20L to null,
            31L to 1003L,
            41L to 1004L,
        )
    }

    @Test
    fun `the system category still refuses deletion`() = runTest {
        migrate()

        shouldThrowAny { exec("DELETE FROM category WHERE id = 0") }
    }

    /** Each type's tables in the 43.db shape, and the reads that answer each case once migrated. */
    enum class Type(
        val domainType: ContentType,
        val contentType: Int,
        val ownerColumn: String,
        val historyTable: String,
        val categoryTable: String,
        val categoryTableNow: String,
        val trackTable: String,
        val memberTable: String,
        val unitTable: String,
        val unitColumns: String,
        val unitValues: String,
        val staleView: String,
        val entryIds: String,
        val readOfEntry2: String,
        val historyOwners: String,
    ) {
        MANGA(
            domainType = ContentType.MANGA,
            contentType = 0,
            ownerColumn = "manga_id",
            historyTable = "history",
            categoryTable = "mangas_categories",
            categoryTableNow = "manga_category",
            trackTable = "manga_sync",
            memberTable = "merge_group_manga",
            unitTable = "merged_chapter_unit",
            unitColumns = "derived_number",
            unitValues = "1",
            staleView = "mergedChapterStaleView",
            entryIds = "SELECT id FROM manga ORDER BY id",
            readOfEntry2 = "SELECT user_read FROM chapter WHERE manga_id = 2",
            historyOwners = "SELECT manga_id FROM history",
        ) {
            override fun entry(id: Long, url: String, favorite: Boolean) =
                "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, " +
                    "cover_last_modified, date_added) VALUES ($id, 1, '$url', '${title(
                        id,
                    )}', 0, ${favorite.sql}, 0, 0, 0, " +
                    "0, ${if (favorite) 1000 else 0})"

            override fun title(id: Long) = "m$id"

            override fun chapter(id: Long, entryId: Long, url: String, read: Boolean) =
                "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, " +
                    "source_order, date_fetch, date_upload) VALUES ($id, $entryId, '$url', 'c', ${read.sql}, 0, 0, " +
                    "1, 0, 0, 0)"
        },
        NOVEL(
            domainType = ContentType.NOVELS,
            contentType = 1,
            ownerColumn = "novel_id",
            historyTable = "novel_history",
            categoryTable = "novels_categories",
            categoryTableNow = "novels_categories",
            trackTable = "novel_tracks",
            memberTable = "merge_group_novel",
            unitTable = "merged_novel_chapter_unit",
            unitColumns = "derived_name, derived_number",
            unitValues = "'c', 1",
            staleView = "mergedNovelChapterStaleView",
            entryIds = "SELECT _id FROM novels ORDER BY _id",
            readOfEntry2 = "SELECT read FROM novel_chapters WHERE novel_id = 2",
            historyOwners = "SELECT C.novel_id FROM novel_history H JOIN novel_chapters C ON C._id = H.chapter_id",
        ) {
            override fun entry(id: Long, url: String, favorite: Boolean) =
                "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                    "date_added) VALUES ($id, 's', '$url', '${title(id)}', 0, ${favorite.sql}, 0, 0, " +
                    "${if (favorite) 1000 else 0})"

            override fun title(id: Long) = "n$id"

            override fun chapter(id: Long, entryId: Long, url: String, read: Boolean) =
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, " +
                    "source_order, date_fetch, date_upload) VALUES ($id, $entryId, '$url', 'c', ${read.sql}, 0, " +
                    "1, 0, 0, 0)"
        },
        ;

        abstract fun entry(id: Long, url: String, favorite: Boolean): String

        /** The title [entry] writes. */
        abstract fun title(id: Long): String

        abstract fun chapter(id: Long, entryId: Long, url: String, read: Boolean): String

        protected val Boolean.sql get() = if (this) 1 else 0
    }

    /** Group 1 of two library entries, stitched over the first one's only chapter, 10. */
    private suspend fun stitchedGroup(type: Type) {
        exec(type.entry(id = 1, url = "/a", favorite = true))
        exec(type.entry(id = 2, url = "/b", favorite = true))
        exec(type.chapter(id = 10, entryId = 1, url = "/c/1", read = false))
        exec("INSERT INTO merge_group(_id, content_type) VALUES (1, ${type.contentType})")
        exec("INSERT INTO ${type.memberTable}(group_id, ${type.ownerColumn}) VALUES (1, 1), (1, 2)")
        exec(
            "INSERT INTO ${type.unitTable}(chapter_id, group_id, unit, copy_order, ${type.unitColumns}) " +
                "VALUES (10, 1, 0, 0, ${type.unitValues})",
        )
    }

    /**
     * One transaction, as the production driver migrates, since a migration's temp tables live only as
     * long as its connection. Foreign keys are off, sqlite-jdbc's default. Returns the rows the
     * foreign-key check reports, which the production driver throws on.
     */
    private suspend fun migrate(): List<Long> = DatabaseBindings.providesDatabase(driver).transactionWithResult {
        Database.Schema.migrate(driver, 43, Database.Schema.version).await()
        longs("SELECT rowid FROM pragma_foreign_key_check")
    }

    private fun novelChapter(id: Long, novelId: Long, read: Boolean, progress: Long, number: Int) =
        "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
            "chapter_number, source_order, date_fetch, date_upload) VALUES ($id, $novelId, '/c/$id', 'c', " +
            "${if (read) 1 else 0}, 0, $progress, $number, 0, 0, 0)"

    private suspend fun pairs(sql: String): List<Pair<Long, Long?>> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(cursor.getLong(0)!! to cursor.getLong(1))
                },
            )
        },
        0,
    ).await()

    private suspend fun exec(sql: String) {
        driver.execute(null, sql, 0).await()
    }

    private suspend fun longs(sql: String): List<Long> = driver.executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(cursor.getLong(0)!!)
                },
            )
        },
        0,
    ).await()
}
