package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Fans a single related-mangas request out across the tracker recommendation endpoints and pushes each
 * successful batch into the shared accumulator via [pushResults], alongside the source-native stream.
 * One tracker failing or timing out never blocks the others. Only trackers the manga is not tracked on
 * reach here (a tracked one goes through the loader's media-context fetch), so each resolves the manga
 * with a single title search. Kitsu and Bangumi have no recommendations endpoint and register no provider.
 */
@Inject
class RecommendationsFetcher(
    private val trackerManager: TrackerManager,
    private val preferences: ReikaiRecommendationPreferences,
    private val providers: RecommendationProviders,
) {

    suspend fun fetch(
        title: String,
        skipTrackerIds: Set<Long>,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        val enabledTrackerIds = preferences.enabledRecommendationTrackerIds(trackerManager)
        if (enabledTrackerIds.isEmpty()) return

        suspend fun run(trackerId: Long) {
            // Skip trackers already handled by the loader's shared media-context fetch (where M is
            // tracked), so recs(M) isn't queried twice.
            if (trackerId in skipTrackerIds) return
            val provider = providers.forTracker(trackerId) ?: return
            runOne(provider, title, pushResults)
        }

        coroutineScope {
            enabledTrackerIds.forEach { trackerId -> launch { run(trackerId) } }
        }
    }

    private suspend fun runOne(
        provider: TrackerRecommendations,
        title: String,
        pushResults: suspend (List<RelatedMangaCandidate>) -> Unit,
    ) {
        cappedRecommendationCall({ "Tracker recommendations fetch failed (${provider.trackerName})" }) {
            provider.getRecsBySearch(title)
        }
            ?.takeIf { it.isNotEmpty() }
            ?.let { pushResults(it) }
    }
}
