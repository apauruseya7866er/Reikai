package reikai.data.coil

import android.content.Context
import coil3.ImageLoader
import coil3.request.Options
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverMetadata
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.NovelCover
import reikai.novel.network.NovelImageClient
import reikai.novel.network.NovelImageRequests
import reikai.util.idleAppScope
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

/**
 * Manga and novel covers share one fetcher, and each content type hands it the client and headers its
 * source resolves; a cover host that refuses a request without the source's Referer depends on it. Each
 * case runs the type's own resolver from a source that carries the Referer.
 */
class CoverRequestClientTest {

    @TempDir
    lateinit var dir: File

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a cover is requested with the headers its source resolves`(type: Type) = runTest {
        var sent: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sent = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("cover".toResponseBody())
                    .build()
            }
            .build()
        val context = mockk<Context> { every { getExternalFilesDir(any()) } returns dir }
        val metadata = MangaCoverMetadata(
            testAppScope(),
            UiPreferences(InMemoryPreferenceStore()),
            CoverCache(idleAppScope(), context),
        )

        type.fetcher(client, Headers.headersOf("Referer", SITE), context, dir, metadata).fetch()

        sent?.header("Referer") shouldBe SITE
    }

    enum class Type {
        MANGA {
            override fun fetcher(
                client: OkHttpClient,
                headers: Headers,
                context: Context,
                dir: File,
                metadata: MangaCoverMetadata,
            ): MangaCoverFetcher {
                val source = mockk<HttpSource> {
                    every { this@mockk.client } returns client
                    every { this@mockk.headers } returns headers
                }
                return MangaCoverFetcher(
                    url = "$SITE/cover.jpg",
                    isLibraryManga = false,
                    mangaCover = null,
                    options = Options(context),
                    coverFileLazy = lazy { null },
                    customCoverFileLazy = lazy { File(dir, "custom") },
                    diskCacheKeyLazy = lazy { "cover" },
                    getClient = { source.coverRequestClient(lazy { error("the source's own client is used") }) },
                    imageLoader = mockk { every { diskCache } returns null },
                    mangaCoverMetadata = metadata,
                )
            }
        },
        NOVEL {
            override fun fetcher(
                client: OkHttpClient,
                headers: Headers,
                context: Context,
                dir: File,
                metadata: MangaCoverMetadata,
            ): MangaCoverFetcher {
                val requests = mockk<NovelImageRequests> {
                    coEvery { forSource("src") } returns NovelImageClient(client, headers)
                }
                val imageLoader = mockk<ImageLoader> {
                    every { diskCache } returns null
                    every { components.key(any(), any()) } returns "cover"
                }
                return NovelCoverFactory(lazy { requests }, CoverCache(idleAppScope(), context), metadata).create(
                    NovelCover(url = "$SITE/cover.jpg", sourceId = "src", isNovelFavorite = false, lastModified = null),
                    Options(context),
                    imageLoader,
                ) as MangaCoverFetcher
            }
        },
        ;

        abstract fun fetcher(
            client: OkHttpClient,
            headers: Headers,
            context: Context,
            dir: File,
            metadata: MangaCoverMetadata,
        ): MangaCoverFetcher
    }

    private companion object {
        const val SITE = "https://example.org"
    }
}
