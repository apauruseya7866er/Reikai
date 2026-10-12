package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupSortOverridesStored
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A novel's chapter flags as the restorer receives them. A backup written before the title display had
 * its own local bit gets 46.sqm's remap; the literals are 46.sqm's, so the two read alike.
 */
class NovelDisplayBitRestoreTest {

    @Test
    fun `an unmarked backup's local sort also sets the local display`() = runTest {
        restoredFlags(Backup(backupManga = emptyList())) shouldBe SORT_AND_DISPLAY_LOCAL
    }

    @Test
    fun `a backup with the sort-override marker keeps its flags`() = runTest {
        val backup = Backup(backupManga = emptyList(), backupSortOverridesStored = BackupSortOverridesStored())
        restoredFlags(backup) shouldBe SORT_LOCAL
    }

    @Test
    fun `a backup naming its novel sources keeps its flags`() = runTest {
        val backup = Backup(backupManga = emptyList(), backupNovelSources = listOf(BackupNovelSource("S", "s")))
        restoredFlags(backup) shouldBe SORT_LOCAL
    }

    @Test
    fun `an unmarked backup without a local sort keeps its flags`() = runTest {
        restoredFlags(Backup(backupManga = emptyList()), flags = 0x00000100L) shouldBe 0x00000100L
    }

    private suspend fun restoredFlags(backup: Backup, flags: Long = SORT_LOCAL): Long {
        val novels = mutableListOf<BackupNovel>()
        restoreEncoded(
            backup.copy(backupNovels = listOf(BackupNovel(source = "s", url = "a", chapterFlags = flags))),
            RestoreOptions(libraryEntries = true, categories = false, appSettings = false),
            novelRestorer = mockk<NovelRestorer>(relaxed = true) {
                coEvery { restore(any(), any()) } coAnswers { novels += firstArg<BackupNovel>() }
            },
        )
        return novels.single().chapterFlags
    }

    private companion object {
        // 46.sqm: chapter_flags | 67108864 WHERE chapter_flags & 16777216 != 0
        const val SORT_LOCAL = 16777216L
        const val SORT_AND_DISPLAY_LOCAL = 16777216L or 67108864L
    }
}
