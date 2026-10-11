package reikai.data.novel

import reikai.domain.novel.model.NovelChapter

/**
 * What [syncChaptersWithNovelSource] did. [newChapters] leaves out what
 * [reikai.domain.chapter.chapterArrivals] flags, as the manga sync's return does. [foundRelease] is what
 * [reikai.domain.chapter.foundRelease] made of its arrivals, each page judged against what it held.
 */
data class NovelChapterSyncResult(
    val newChapters: List<NovelChapter>,
    val changed: Boolean,
    val foundRelease: Boolean,
) {
    operator fun plus(other: NovelChapterSyncResult) = NovelChapterSyncResult(
        newChapters + other.newChapters,
        changed || other.changed,
        foundRelease || other.foundRelease,
    )

    companion object {
        val UNCHANGED = NovelChapterSyncResult(emptyList(), changed = false, foundRelease = false)
    }
}
