package reikai.data.coil

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.data.coil.ExtensionIconFetcher
import eu.kanade.tachiyomi.extension.ExtensionManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.NovelIconHints
import reikai.domain.source.siteUrls

private const val EXTENSION_ICON_SCHEME = "reikai-extension-icon"

/**
 * An address for an installed extension app's own icon. Source icons are addresses everywhere a novel
 * source is drawn, and an app's icon lives in its package rather than at a URL, so it gets one here.
 */
fun extensionIconUrl(pkgName: String): String = "$EXTENSION_ICON_SCHEME://$pkgName"

/** Whether an icon has no visible pixel, which every IReader app's does: it is as good as none. */
fun Bitmap.isInvisible(): Boolean {
    val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    return pixels.all { it ushr 24 == 0 }
}

/**
 * The icons an app with a blank one may borrow. A listing can name an address in this fetcher's own
 * scheme, which would only come back here, so none is taken.
 */
internal fun borrowableIcons(hints: NovelIconHints, pkgName: String, siteUrls: List<String?>): List<String> =
    hints.candidatesFor(pkgName, siteUrls).filterNot { it.startsWith("$EXTENSION_ICON_SCHEME:") }

/**
 * Finds a stand-in for an app whose own icon shows nothing: an icon its store or another listing gives for
 * its site, the first that loads. Only a novel extension names its sites, so a manga one finds none.
 */
class ExtensionIconBorrower(
    private val extensionManager: Lazy<ExtensionManager>,
    private val novelPreferences: Lazy<NovelPreferences>,
) {
    suspend fun borrow(pkgName: String, options: Options, imageLoader: ImageLoader): FetchResult? {
        val sites = extensionManager.value.getLoadedNovelExtensions()
            .firstOrNull { it.pkgName == pkgName }
            ?.sources.orEmpty()
            .flatMap { it.siteUrls }
        for (url in borrowableIcons(novelPreferences.value.novelIconHints().get(), pkgName, sites)) {
            val result = imageLoader.execute(ImageRequest.Builder(options.context).data(url).build())
            if (result is SuccessResult) {
                return ImageFetchResult(result.image, isSampled = false, dataSource = DataSource.NETWORK)
            }
        }
        return null
    }
}

/** Draws the icon an [extensionIconUrl] names through the installed extension icon fetcher. */
class ExtensionIconUrlFactory(private val borrower: ExtensionIconBorrower) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
        data.authority?.takeIf { data.scheme == EXTENSION_ICON_SCHEME }?.let { pkgName ->
            ExtensionIconFetcher(
                context = options.context,
                getPkgName = { pkgName },
                density = null,
                borrowIcon = { borrower.borrow(it, options, imageLoader) },
            )
        }
}
