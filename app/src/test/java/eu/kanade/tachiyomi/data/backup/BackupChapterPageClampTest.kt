package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A backup carries a chapter's page and page count as int64, and a file is the one place either
 * arrives from outside the app. A plain narrow to Int wraps an oversized value to a negative index,
 * so the restore clamps it into range instead.
 */
class BackupChapterPageClampTest {

    private fun restored(lastPageRead: Long = 0, pageCount: Long = 0) =
        BackupChapter(url = "c", name = "C", lastPageRead = lastPageRead, pageCount = pageCount).toChapterImpl()

    @Test
    fun `a page past the Int range restores as the highest page rather than a negative one`() {
        restored(lastPageRead = Int.MAX_VALUE + 1L).lastPageRead shouldBe Int.MAX_VALUE
    }

    @Test
    fun `a negative page restores as the first page`() {
        restored(lastPageRead = -5).lastPageRead shouldBe 0
    }

    @Test
    fun `a page count past the Int range restores as the highest count rather than a negative one`() {
        restored(pageCount = Int.MAX_VALUE + 1L).pageCount shouldBe Int.MAX_VALUE
    }
}
