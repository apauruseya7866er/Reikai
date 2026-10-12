package reikai.domain.track

import io.mockk.coEvery
import io.mockk.mockk
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.chapter.model.Chapter

/** A [TrackedChapterNumber] answering every chapter with its own number, as for an unmerged series. */
fun ownChapterNumbers(): TrackedChapterNumber = mockk {
    coEvery { ofManga(any()) } answers { firstArg<List<Chapter>>().map { it.chapterNumber } }
    coEvery { ofNovels(any()) } answers { firstArg<List<NovelChapter>>().map { it.chapterNumber } }
}
