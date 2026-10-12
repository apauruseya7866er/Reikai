package reikai.data.recents

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.NovelCover
import reikai.domain.recents.RecentlyAddedManga
import reikai.domain.recents.RecentlyAddedNovel
import reikai.domain.recents.RecentlyAddedRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.manga.model.MangaCover
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RecentlyAddedRepositoryImpl(
    private val database: Database,
) : RecentlyAddedRepository {

    override fun subscribeManga(
        after: Instant,
        limit: Long,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<RecentlyAddedManga>> =
        database.recentlyAddedViewQueries.getRecentlyAddedManga(
            after = after,
            includedEmpty = includedCategories.isEmpty(),
            includedCategories = includedCategories,
            excludedEmpty = excludedCategories.isEmpty(),
            excludedCategories = excludedCategories,
            limit = limit,
            mapper = ::mapRecentlyAddedManga,
        ).subscribeToList()

    override fun subscribeNovels(
        after: Instant,
        limit: Long,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<RecentlyAddedNovel>> =
        database.recentlyAddedViewQueries.getRecentlyAddedNovels(
            after = after,
            includedEmpty = includedCategories.isEmpty(),
            includedCategories = includedCategories,
            excludedEmpty = excludedCategories.isEmpty(),
            excludedCategories = excludedCategories,
            limit = limit,
            mapper = ::mapRecentlyAddedNovel,
        ).subscribeToList()
}

// Both queries select only favorited entries, so the cover's favourite flag is true by construction. The
// date added arrives as millis because the coalesce that makes the column non-null drops its adapter.
private fun mapRecentlyAddedManga(
    mangaId: Long,
    title: String,
    source: Long,
    thumbnailUrl: String?,
    coverLastModified: Instant?,
    dateAdded: Long,
): RecentlyAddedManga = RecentlyAddedManga(
    mangaId = mangaId,
    title = title,
    dateAdded = Instant.fromEpochMilliseconds(dateAdded),
    coverData = MangaCover(
        mangaId = mangaId,
        sourceId = source,
        isMangaFavorite = true,
        url = thumbnailUrl,
        lastModified = coverLastModified,
    ),
)

private fun mapRecentlyAddedNovel(
    novelId: Long,
    title: String,
    source: String,
    thumbnailUrl: String?,
    coverLastModified: Instant?,
    dateAdded: Long,
): RecentlyAddedNovel = RecentlyAddedNovel(
    novelId = novelId,
    title = title,
    dateAdded = Instant.fromEpochMilliseconds(dateAdded),
    coverData = NovelCover(
        url = thumbnailUrl,
        sourceId = source,
        isNovelFavorite = true,
        lastModified = coverLastModified,
        novelId = novelId,
    ),
)
