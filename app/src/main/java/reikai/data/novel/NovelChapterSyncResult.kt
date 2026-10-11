package reikai.data.novel

import reikai.domain.novel.model.NovelChapter

/**
 * What [syncChaptersWithNovelSource] did. [newChapters] leaves out what
 * [reikai.domain.chapter.chapterArrivals] flags, as the manga sync's return does. [hadChapters] is
 * whether the novel had chapters stored before the sync, and [foundRelease] what
 * [reikai.domain.chapter.foundRelease] made of its arrivals.
 */
data class NovelChapterSyncResult(
    val newChapters: List<NovelChapter>,
    val changed: Boolean,
    val hadChapters: Boolean,
    val foundRelease: Boolean,
) {
    /**
     * This sync followed by [other], as a page walk adds them up. Whether the novel had chapters is this
     * first sync's: a later page syncs against the pages before it, so on a first fetch it would count
     * the back catalogue as a release.
     */
    operator fun plus(other: NovelChapterSyncResult) = NovelChapterSyncResult(
        newChapters + other.newChapters,
        changed || other.changed,
        hadChapters,
        hadChapters && (foundRelease || other.foundRelease),
    )

    companion object {
        /** No page synced at all, which still predicts as an unchanged list does. */
        val UNCHANGED = NovelChapterSyncResult(emptyList(), changed = false, hadChapters = false, foundRelease = false)
    }
}
