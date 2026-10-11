package reikai.presentation.stats

import eu.kanade.tachiyomi.data.track.Tracker
import reikai.domain.library.SmartUpdateFacts
import reikai.domain.merge.EntryMergeManager
import reikai.domain.merge.MergeBucket
import reikai.domain.merge.MergedGroupCounts
import reikai.presentation.library.libraryTrackerMeans
import reikai.presentation.library.mergedGroupTracks
import tachiyomi.domain.track.model.Track

/**
 * One content type's library as Statistics counts it. A merged series is one title, its lowest-id
 * member standing for it in the status stats, with its read and unread taken from the stored stitch
 * ([groupCounts], by group id) as its library card takes them. Per-source rows and files (chapters,
 * downloads) sum over every member and its trackers are read across them, as the library card reads them.
 */
class StatsSeries<T>(
    private val buckets: List<MergeBucket<T>>,
    private val groupCounts: Map<Long, MergedGroupCounts>,
    private val id: (T) -> Long,
) {

    val titles: List<T> = buckets.map { it.members.first() }

    val members: List<T> = buckets.flatMap { it.members }

    private val membersByTitle: Map<Long, List<Long>> =
        buckets.associate { bucket -> id(bucket.members.first()) to bucket.members.map(id) }

    /** Mihon's completed count: titles whose status is completed and that have nothing left unread. */
    fun completedCount(facts: (T) -> SmartUpdateFacts): Int = buckets.count { bucket ->
        val title = facts(bucket.members.first())
        title.isCompleted && (stitchOf(bucket)?.unread ?: title.unreadCount) == 0L
    }

    /** Titles with a chapter read, as the library's Started filter reads them. */
    fun startedCount(facts: (T) -> SmartUpdateFacts): Int = buckets.count { bucket ->
        stitchOf(bucket)?.let { it.read > 0 } ?: facts(bucket.members.first()).hasStarted
    }

    /** Titles the global update reaches. Each source of a merged series is updated on its own, so the
     *  series counts once when [isUpdated] holds for any of them. */
    fun globalUpdateCount(isUpdated: (T) -> Boolean): Int = buckets.count { it.members.any(isUpdated) }

    fun trackedCount(tracksById: Map<Long, List<Track>>): Int =
        membersByTitle.values.count { mergedGroupTracks(it, tracksById).isNotEmpty() }

    /** Each scored title's mean 0-10 score over [trackers], the logged-in ones. */
    fun meanScores(tracksById: Map<Long, List<Track>>, trackers: Map<Long, Tracker>): Collection<Double> =
        libraryTrackerMeans(membersByTitle, tracksById, trackers).values

    // An unstitched group falls back to its title's own counts, as its library row does.
    private fun stitchOf(bucket: MergeBucket<T>): MergedGroupCounts? = bucket.groupId?.let(groupCounts::get)
}

suspend fun <T> EntryMergeManager.statsSeries(
    library: List<T>,
    groupCounts: Map<Long, MergedGroupCounts>,
    id: (T) -> Long,
): StatsSeries<T> = StatsSeries(seriesBuckets(library, id), groupCounts, id)
