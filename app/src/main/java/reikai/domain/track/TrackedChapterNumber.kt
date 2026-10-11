package reikai.domain.track

import dev.zacsweers.metro.Inject
import reikai.domain.library.ContentType
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.MergedGroupStitcher
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.chapter.model.Chapter

/**
 * The number a chapter push sends for a chapter just read: the number of the trunk's copy of the same
 * merged chapter, because a merged series is tracked by the trunk's numbering and a sibling source
 * counts its own way. A merged chapter the trunk has no copy of (a gap-fill), a chapter the stitch did
 * not place and a chapter of an unmerged series keep their own number. Both content types answer here,
 * over the stored stitch rebuilt if stale, so every push site and the delayed queue get one number.
 */
@Inject
class TrackedChapterNumber(
    private val reconcile: ReconcileMergedChapters,
    private val stitchers: Set<MergedGroupStitcher>,
    private val mangaMergeManager: MangaMergeManager,
    private val novelMergeManager: NovelMergeManager,
) {

    /** Each of [chapters]' number, in order; one stitch read per merge group however many there are. */
    suspend fun ofManga(chapters: List<Chapter>): List<Double> =
        of(ContentType.MANGA, mangaMergeManager, chapters.map { Read(it.mangaId, it.id, it.chapterNumber) })

    /** Each of [chapters]' number, in order; one stitch read per merge group however many there are. */
    suspend fun ofNovels(chapters: List<NovelChapter>): List<Double> =
        of(ContentType.NOVELS, novelMergeManager, chapters.map { Read(it.novelId, it.id, it.chapterNumber) })

    private class Read(val entryId: Long, val chapterId: Long, val ownNumber: Double)

    private suspend fun of(contentType: ContentType, mergeManager: EntryMergeManager, reads: List<Read>): List<Double> {
        val groupOf = HashMap<Long, Long?>()
        val groups = HashMap<Long, GroupNumbers>()
        return reads.map { read ->
            if (read.entryId !in groupOf) groupOf[read.entryId] = mergeManager.groupIdOf(read.entryId)
            val groupId = groupOf[read.entryId] ?: return@map read.ownNumber
            groups.getOrPut(groupId) {
                GroupNumbers(reconcile.currentStitch(contentType, groupId)) {
                    stitchers.single { it.contentType == contentType }.trunkNumbers(groupId)
                }
            }.numberOf(read)
        }
    }

    /** One group's answers, loading the trunk's chapters only once a read shares its merged chapter. */
    private class GroupNumbers(stitch: List<ChapterUnit>, private val loadTrunk: suspend () -> Map<Long, Double>) {
        private val unitOf = stitch.associate { it.chapterId to it.unit }
        private val copiesOf = stitch.groupBy { it.unit }
        private var trunk: Map<Long, Double>? = null

        suspend fun numberOf(read: Read): Double {
            val copies = unitOf[read.chapterId]?.let(copiesOf::getValue)
            // A copy alone in its merged chapter is the trunk's own or a gap-fill, and both keep their number.
            if (copies == null || copies.size == 1) return read.ownNumber
            val trunk = trunk ?: loadTrunk().also { trunk = it }
            return copies.filter { it.chapterId in trunk }.minByOrNull { it.copyOrder }
                ?.let { trunk.getValue(it.chapterId) }
                ?: read.ownNumber
        }
    }
}
