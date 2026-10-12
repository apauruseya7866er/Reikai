package reikai.domain.recommendation

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.domain.manga.interactor.GetFavorites

/**
 * Which related cards are in the library, following the library live, for the details carousel and its
 * "See all" grid alike. The hide filter's index is a snapshot taken when the assembly was built, so a
 * title added after that reads as in the library only through these keys.
 */
@Inject
class RelatedLibraryKeys(private val getFavorites: GetFavorites) {

    /** The (url, source) keys of library manga on the sources [entries]' candidates come from. */
    fun of(entries: Flow<RelatedMangaCache.Entry?>): Flow<Set<Pair<String, Long>>> =
        entries.map { entry ->
            entry?.fullPool.orEmpty().map { it.sourceId }.filter { it != RECOMMENDS_SOURCE }.toSortedSet()
        }
            .distinctUntilChanged()
            .flatMapLatest(::keysOn)

    private fun keysOn(sourceIds: Set<Long>): Flow<Set<Pair<String, Long>>> =
        if (sourceIds.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(sourceIds.map { getFavorites.subscribe(it) }) { perSource ->
                perSource.flatMap { favorites -> favorites.map { it.url to it.source } }.toSet()
            }
        }
}

/** Whether [candidate] is in the library: by the live [keys], or by a tracker or title match the filter made. */
fun RecommendationHideFilter.isInLibrary(candidate: RelatedMangaCandidate, keys: Set<Pair<String, Long>>): Boolean =
    (candidate.manga.url to candidate.sourceId) in keys || isInLibrary(candidate)
