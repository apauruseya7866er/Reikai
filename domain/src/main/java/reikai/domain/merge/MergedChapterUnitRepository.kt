package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType

/**
 * Storage for what the cross-source stitch decided about a merge group's chapters. See
 * `data/merged_chapter_unit.sq` for why it is a rebuildable cache and why it is kept current by
 * reconciliation rather than by hooking every chapter write.
 */
interface MergedChapterUnitRepository {

    /**
     * Claims every group of [contentType] a write may have left stale since the last claim, plus any a
     * claim before left unfinished. A superset of the stale groups: each still needs [isStale].
     */
    suspend fun claimPendingGroups(contentType: ContentType): List<Long>

    /** Drops [groupId]'s claim once its exact check ran; a write since keeps it listed. */
    suspend fun releaseClaim(groupId: Long)

    /** Whether [groupId]'s stored stitch no longer matches the chapters behind it, at that group's cost. */
    suspend fun isStale(contentType: ContentType, groupId: Long): Boolean

    /** The ranking stamp each group was last stitched under, keyed by group; see [rankingStamps]. */
    suspend fun getRankings(): Map<Long, String>

    /**
     * [groupId]'s stored stitch, in merged order, for a screen about to render it. Chapters the stitch
     * dropped are left out, since they render nowhere. Empty when the group has never been stitched.
     */
    suspend fun getStitch(contentType: ContentType, groupId: Long): List<ChapterUnit>

    /**
     * Replace [groupId]'s stored stitch with [stitch] and the [ranking] stamp it was built under,
     * clearing what was there first, in one transaction. A stitch with no units clears the group, which
     * is what a group with too few library members left to stitch comes to; a null [ranking] records none.
     */
    suspend fun replaceGroup(contentType: ContentType, groupId: Long, stitch: GroupStitch, ranking: String?)

    /**
     * Chapter counts per merge group, keyed by group. Every stitched group has an entry, zeros included.
     * A missing group has not been stitched yet, which a caller must show as its leading source's own
     * counts.
     */
    suspend fun getGroupCounts(contentType: ContentType): Map<Long, MergedGroupCounts>

    /** Reactive [getGroupCounts]: re-emits when the counts change, so a badge is not left showing what
     *  the group looked like before it was stitched. A write that leaves them as they were emits nothing,
     *  since every chapter write re-runs the query and the library rebuilds on each emission. */
    fun getGroupCountsAsFlow(contentType: ContentType): Flow<Map<Long, MergedGroupCounts>>

    /**
     * The merged chapters [ownerIds] hold as counted copies, keyed by group, with what a download probe
     * needs to find the file. Whether one is on disk lives on disk, not here, so the count is the caller's
     * to take: it probes these rows and counts the merged chapters that answer. A caller passes only the
     * members that have downloaded something, since no other member can hold a copy.
     */
    suspend fun getDownloadUnits(contentType: ContentType, ownerIds: Set<Long>): Map<Long, List<DownloadUnitRow>>

    /**
     * Emits on collection and after every stitch written, whether or not anything it stores changed, so a
     * reader of [getDownloadUnits] knows to read again after a restitch the counts cannot see.
     */
    fun getStitchWritesAsFlow(): Flow<Unit>

    /**
     * Every library copy of each of [chapterIds]' merged chapters, keyed by the chapter asked for: what a
     * row naming one copy probes to say whether the copy a tap opens is on disk. Besides that chapter, the
     * same copies [getDownloadUnits] counts. The chapter asked for is always among its own copies,
     * even off the library or under an excluded scanlator, since callers look it up by id. A chapter no
     * stitched group places has no entry.
     */
    fun getCopiesAsFlow(contentType: ContentType, chapterIds: Collection<Long>): Flow<Map<Long, List<ChapterCopyRow>>>

    /**
     * How many distinct recognized chapter numbers each grouped library manga lists, keyed by manga id:
     * the count the stitch ranks the trunk on, so the collapsed library row leads on the same source the
     * details chapter list does. The count the last stitch saved, so it moves when a reconcile does; a
     * member its group has not been stitched with yet is counted from its chapter rows. A manga absent
     * from the map lists no recognized number, which ranks as zero. Emits only when a count changed,
     * like [getGroupCountsAsFlow].
     */
    fun getRecognizedChapterCountsAsFlow(): Flow<Map<Long, Long>>

    /**
     * What one stitch of a group stores: every chapter's place in it, and [recognizedCounts], each manga
     * member's id to the count the stitch ranked it on, which the library ranks the same members by.
     * Always empty for novels, whose library row already carries the row count the novel stitch ranks on.
     */
    data class GroupStitch(
        val units: List<StoredUnit>,
        val recognizedCounts: Map<Long, Long>,
    )

    /**
     * One chapter's place in its group's stitch. [unit] is its position in the merged list, null when
     * the stitch dropped it. The derived values are the stitch's inputs, stored so a changed chapter
     * reads as stale: the identity's (which of name and number matter is per content type, and the
     * other is written anyway so one shape serves both tables) and [sourceOrder], its place in the walk.
     */
    data class StoredUnit(
        val chapterId: Long,
        val unit: Int?,
        val copyOrder: Int,
        val chapterName: String,
        val chapterNumber: Double,
        val sourceOrder: Long,
    )
}
