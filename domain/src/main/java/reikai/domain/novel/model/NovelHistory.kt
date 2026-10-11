package reikai.domain.novel.model

import androidx.compose.runtime.Immutable
import kotlin.time.Instant

/**
 * One History-tab novel row: a novel's most-recently-read chapter plus its cover. Novel twin of
 * [tachiyomi.domain.history.model.HistoryWithRelations], type only. [readAt] is nullable to mirror the
 * manga model, though the feed query only returns rows that have one.
 */
@Immutable
data class NovelHistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val novelId: Long,
    val title: String,
    val chapterNumber: Double,
    val readAt: Instant?,
    val readDuration: Long,
    val coverData: NovelCover,
    // The recents read lane acts on the chapter this row names, so it carries that chapter's own
    // state and the values its download state is looked up by. No scanlator, which a novel's download
    // name does not carry, and progress is a hundredths scroll percent where the manga twin counts pages.
    val chapterName: String,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastTextProgress: Long,
    val source: String,
    /**
     * The title as stored, which the display-only custom-title overlay does not rewrite. A download
     * folder is named from this one, so looking a download up by [title] finds nothing as soon as
     * the user renames the entry.
     */
    val storedTitle: String,
)

/** One chapter's history row, the novel twin of [tachiyomi.domain.history.model.History], type only. */
data class NovelHistory(
    val chapterId: Long,
    val readAt: Instant?,
    val readDuration: Long,
)

/**
 * Reader write payload, twin of [tachiyomi.domain.history.model.HistoryUpdate], type only.
 * [sessionReadDuration] is the time spent in the chapter this session, accumulated into
 * `time_read` on upsert, pinned by HistoryFeedConformanceTest.
 */
data class NovelHistoryUpdate(
    val chapterId: Long,
    val readAt: Instant,
    val sessionReadDuration: Long,
)
