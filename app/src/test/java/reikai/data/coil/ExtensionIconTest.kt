package reikai.data.coil

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.data.coil.ExtensionIconFetcher
import eu.kanade.tachiyomi.data.coil.InstalledExtensionIcon
import eu.kanade.tachiyomi.data.coil.InstalledExtensionIconKeyer
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.source.NovelIconHints

class ExtensionIconTest {

    @AfterEach
    fun tearDown() {
        unmockkObject(ExtensionLoader)
    }

    /** Fetched, it would come back to the fetcher that asked, which asks again without end. */
    @Test
    fun `an icon listed in the app's own icon address is not borrowed`() {
        val hints = NovelIconHints(packages = mapOf("ireader.a.en" to extensionIconUrl("ireader.b.en")))

        borrowableIcons(hints, "ireader.a.en", emptyList()) shouldBe emptyList()
    }

    @Test
    fun `an icon on the web is borrowed`() {
        val hints = NovelIconHints(packages = mapOf("ireader.a.en" to "https://store/icon.png"))

        borrowableIcons(hints, "ireader.a.en", emptyList()) shouldBe listOf("https://store/icon.png")
    }

    /** A new version can change the icon, and each density draws its own, so neither may share a cache entry. */
    @Test
    fun `an installed extension icon is cached per version and per density`() {
        val keyer = InstalledExtensionIconKeyer()
        val options = mockk<Options>()

        setOf(
            keyer.key(InstalledExtensionIcon(PKG, versionCode = 1, density = 160), options),
            keyer.key(InstalledExtensionIcon(PKG, versionCode = 2, density = 160), options),
            keyer.key(InstalledExtensionIcon(PKG, versionCode = 1, density = 640), options),
        ).size shouldBe 3
    }

    /** It fails so the row draws the default icon instead of an empty square. */
    @Test
    fun `an installed icon with no visible pixel and nothing to borrow fails`() = runTest {
        val fetcher = fetcherFor(iconPixel = TRANSPARENT)

        shouldThrow<IllegalStateException> { fetcher.fetch() }
    }

    @Test
    fun `an installed icon with a visible pixel is drawn`() = runTest {
        val fetcher = fetcherFor(iconPixel = OPAQUE_BLACK)

        fetcher.fetch().shouldBeInstanceOf<ImageFetchResult>()
    }

    /** An app whose package icon is a 2x2 bitmap filled with [iconPixel]; the package manager is the boundary. */
    private fun fetcherFor(iconPixel: Int): ExtensionIconFetcher {
        val bitmap = mockk<Bitmap>(relaxed = true) {
            every { width } returns 2
            every { height } returns 2
            every { getPixels(any(), any(), any(), any(), any(), any(), any()) } answers
                { firstArg<IntArray>().fill(iconPixel) }
        }
        val drawable = mockk<BitmapDrawable> {
            every { this@mockk.bitmap } returns bitmap
            every { intrinsicWidth } returns 2
            every { intrinsicHeight } returns 2
        }
        val context = mockk<Context>(relaxed = true)
        val appInfo = mockk<ApplicationInfo> { every { loadIcon(any()) } returns drawable }
        val pkgInfo = mockk<PackageInfo>().also { it.applicationInfo = appInfo }
        mockkObject(ExtensionLoader)
        every { ExtensionLoader.getExtensionPackageInfoFromPkgName(context, PKG) } returns pkgInfo
        return ExtensionIconFetcher(context, { PKG }, density = null)
    }

    private companion object {
        const val PKG = "ireader.a.en"
        const val TRANSPARENT = 0x00FFFFFF
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
