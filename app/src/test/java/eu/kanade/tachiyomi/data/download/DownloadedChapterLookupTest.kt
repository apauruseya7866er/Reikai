package eu.kanade.tachiyomi.data.download

import android.net.Uri
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * Queueing a whole series asks which of its chapters are already on disk (mihon f02bf5ba7). A storage access
 * framework folder answers each name lookup with a content resolver query, so a long list is matched against
 * one listing there; a plain folder's listing can leave out files another app made, so it is never trusted.
 */
class DownloadedChapterLookupTest {

    private val chapters = (1L..60L).map { Chapter.create().copy(id = it, name = "Ch $it", url = "u$it") }

    @Test
    fun `a long list on a document tree is matched against one listing, ignoring case`() = runTest {
        val listed = chapters.map { folder(provider.dirNameOf(it).uppercase()) }.toTypedArray()
        val mangaDir = mockk<UniFile> {
            every { uri } returns uri("content")
            every { listFiles() } returns listed
        }

        provider(mangaDir).findDownloadedChapterIds(chapters, MANGA, mockk(relaxed = true)) shouldBe
            chapters.mapTo(HashSet()) { it.id }
    }

    @Test
    fun `a long list in a plain folder is looked up by name, whatever its listing leaves out`() = runTest {
        val onDisk = chapters.mapTo(HashSet()) { provider.dirNameOf(it) }
        val mangaDir = mockk<UniFile> {
            every { uri } returns uri("file")
            every { listFiles() } returns emptyArray()
            every { findFile(any()) } answers { firstArg<String>().takeIf { it in onDisk }?.let(::folder) }
        }

        provider(mangaDir).findDownloadedChapterIds(chapters, MANGA, mockk(relaxed = true)) shouldBe
            chapters.mapTo(HashSet()) { it.id }
    }

    private val provider = provider(mockk())

    private fun DownloadProvider.dirNameOf(chapter: Chapter) =
        getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).first()

    /** Downloads root, then its source folder, then [mangaDir], each found by name. */
    private fun provider(mangaDir: UniFile): DownloadProvider {
        val sourceDir = mockk<UniFile> { every { findFile(any()) } returns mangaDir }
        val root = mockk<UniFile> { every { findFile(any()) } returns sourceDir }
        return DownloadProvider(
            context = mockk(),
            storageManager = mockk { every { getDownloadsDirectory() } returns root },
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        )
    }

    private fun folder(name: String) = mockk<UniFile> { every { this@mockk.name } returns name }

    private fun uri(scheme: String) = mockk<Uri> { every { this@mockk.scheme } returns scheme }

    private companion object {
        val MANGA = Manga.create().copy(id = 1L, title = "Title")
    }
}
