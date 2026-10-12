package eu.kanade.tachiyomi.data.cache

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import reikai.domain.entry.EntryId // RK
import reikai.domain.entry.customCoverKey // RK
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Class used to create cover cache.
 * It is used to store the covers of the library.
 * Names of files are created with the md5 of the thumbnail URL.
 *
 * @param context the application context.
 * @constructor creates an instance of the cover cache.
 */
@Inject
@SingleIn(AppScope::class)
class CoverCache(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
) {

    companion object {
        private const val COVERS_DIR = "covers"
        private const val CUSTOM_COVERS_DIR = "covers/custom"
    }

    /**
     * Cache directory used for cache management.
     */
    private val cacheDir by lazy { getCacheDir(COVERS_DIR) }

    private val customCoverCacheDir by lazy { getCacheDir(CUSTOM_COVERS_DIR) }

    private val customCoverNames = mutableSetOf<String>()

    // Deleted while the directory was being listed, so the listing may still contain them
    private val deletedWhileIndexing = mutableSetOf<String>()

    @Volatile
    private var customCoversIndexed = false

    init {
        scope.launch {
            val names = customCoverCacheDir.list().orEmpty()
            synchronized(customCoverNames) {
                names.filterNotTo(customCoverNames) { it in deletedWhileIndexing }
                deletedWhileIndexing.clear()
                customCoversIndexed = true
            }
        }
    }

    /**
     * Returns the cover from cache.
     *
     * @param mangaThumbnailUrl thumbnail url for the manga.
     * @return cover image.
     */
    fun getCoverFile(mangaThumbnailUrl: String?): File? {
        return mangaThumbnailUrl?.let {
            File(cacheDir, DiskUtil.hashKeyForDisk(it))
        }
    }

    /**
     * Returns the custom cover from cache.
     *
     * @param mangaId the manga id.
     * @return cover image.
     */
    fun getCustomCoverFile(mangaId: Long?): File {
        return File(customCoverCacheDir, DiskUtil.hashKeyForDisk(mangaId.toString()))
    }

    fun hasCustomCover(mangaId: Long?): Boolean {
        return isIndexedCustomCover(getCustomCoverFile(mangaId)) // RK: the check novels share
    }

    /**
     * Saves the given stream as the manga's custom cover to cache.
     *
     * @param manga the manga.
     * @param inputStream the stream to copy.
     * @throws IOException if there's any error.
     */
    @Throws(IOException::class)
    fun setCustomCoverToCache(manga: Manga, inputStream: InputStream) {
        val file = getCustomCoverFile(manga.id)
        file.outputStream().use {
            inputStream.copyTo(it)
        }
        indexCustomCover(file) // RK: the index update novels share
    }

    /**
     * Delete the cover files of the manga from the cache.
     *
     * @param manga the manga.
     * @param deleteCustomCover whether the custom cover should be deleted.
     * @return number of files that were deleted.
     */
    fun deleteFromCache(manga: Manga, deleteCustomCover: Boolean = false): Int {
        var deleted = 0

        getCoverFile(manga.thumbnailUrl)?.let {
            if (it.exists() && it.delete()) ++deleted
        }

        if (deleteCustomCover) {
            if (deleteCustomCover(manga.id)) ++deleted
        }

        return deleted
    }

    /**
     * Delete custom cover of the manga from the cache
     *
     * @param mangaId the manga id.
     * @return whether the cover was deleted.
     */
    fun deleteCustomCover(mangaId: Long?): Boolean {
        return deleteIndexedCustomCover(getCustomCoverFile(mangaId)) // RK: the delete novels share
    }

    // RK --> both content types share this one directory, so a custom cover is named by the entry's
    // identity rather than a bare id (a manga and a novel can carry the same row id). A manga's name is
    // unchanged, so only novels needed a one-time move (MigrateNovelCustomCoverKeysMigration).
    fun getCustomCoverFile(entryId: EntryId): File {
        return File(customCoverCacheDir, DiskUtil.hashKeyForDisk(entryId.customCoverKey()))
    }

    fun hasCustomCover(entryId: EntryId): Boolean = isIndexedCustomCover(getCustomCoverFile(entryId))

    fun deleteCustomCover(entryId: EntryId): Boolean = deleteIndexedCustomCover(getCustomCoverFile(entryId))

    // Keyed by entry for novels: twin of the Manga overloads above, pinned by CoverCacheConformanceTest.
    @Throws(IOException::class)
    fun setCustomCoverToCache(entryId: EntryId, inputStream: InputStream) {
        val file = getCustomCoverFile(entryId)
        file.outputStream().use {
            inputStream.copyTo(it)
        }
        indexCustomCover(file)
    }

    fun deleteFromCache(entryId: EntryId, thumbnailUrl: String?, deleteCustomCover: Boolean = false): Int {
        var deleted = 0
        getCoverFile(thumbnailUrl)?.let {
            if (it.exists() && it.delete()) ++deleted
        }
        if (deleteCustomCover && deleteCustomCover(entryId)) ++deleted
        return deleted
    }

    /** Re-reads whether each of [files] exists, for a custom cover a migration moved or deleted directly. */
    fun reindexCustomCovers(vararg files: File) {
        files.forEach { if (it.exists()) indexCustomCover(it) else unindexCustomCover(it) }
    }

    // Both content types share the directory, so the index answers for either
    private fun isIndexedCustomCover(file: File): Boolean {
        if (!customCoversIndexed) return file.exists()
        return synchronized(customCoverNames) { file.name in customCoverNames }
    }

    private fun indexCustomCover(file: File) {
        synchronized(customCoverNames) {
            customCoverNames.add(file.name)
            deletedWhileIndexing.remove(file.name)
        }
    }

    private fun deleteIndexedCustomCover(file: File): Boolean {
        val deleted = file.exists() && file.delete()
        unindexCustomCover(file)
        return deleted
    }

    private fun unindexCustomCover(file: File) {
        synchronized(customCoverNames) {
            customCoverNames.remove(file.name)
            if (!customCoversIndexed) deletedWhileIndexing.add(file.name)
        }
    }
    // RK <--

    private fun getCacheDir(dir: String): File {
        return context.getExternalFilesDir(dir)
            ?: File(context.filesDir, dir).also { it.mkdirs() }
    }
}
