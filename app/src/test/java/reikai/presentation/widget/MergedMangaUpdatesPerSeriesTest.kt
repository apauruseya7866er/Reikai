package reikai.presentation.widget

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.merge.TestMergeManagers
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Instant

/** Manga 1 and 2 are one merged series; Mihon's cover widgets draw through this. */
class MergedMangaUpdatesPerSeriesTest {

    private fun perSeries(mergingOn: Boolean) = MergedMangaUpdatesPerSeries(
        TestMergeManagers(mapOf(ContentType.MANGA to mapOf(1L to 9L, 2L to 9L)), mergingOn).manga,
    )

    private val updates = listOf(update(mangaId = 2L, chapterId = 20L), update(1L, 10L), update(2L, 21L))

    @Test
    fun `a merged manga draws one cover`() = runTest {
        perSeries(mergingOn = true).onePerSeries(updates).map { it.mangaId } shouldBe listOf(2L)
    }

    @Test
    fun `with series merging off each source draws its own cover`() = runTest {
        perSeries(mergingOn = false).onePerSeries(updates).map { it.mangaId } shouldBe listOf(2L, 1L)
    }

    private fun update(mangaId: Long, chapterId: Long) = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = "",
        chapterId = chapterId,
        chapterName = "",
        scanlator = null,
        chapterUrl = "",
        read = false,
        bookmark = false,
        lastPageRead = 0,
        pageCount = 0,
        sourceId = 1L,
        dateFetch = Instant.fromEpochMilliseconds(0L),
        coverData = MangaCover(mangaId, 1L, isMangaFavorite = true, url = null, lastModified = null),
    )
}
