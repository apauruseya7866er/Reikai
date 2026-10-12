package eu.kanade.domain.download.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@Inject
class DeleteDownload(
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
) {

    suspend fun awaitAll(manga: Manga, vararg chapters: Chapter) {
        // RK --> an uninstalled source's stub names the same folder, so its downloads go too, as a novel's do;
        // the mark-read delete is automatic removal, which the kept categories still hold back
        val source = sourceManager.getOrStub(manga.source)
        downloadManager.deleteRemovableChapters(chapters.toList(), manga, source)
        // RK <--
    }
}
