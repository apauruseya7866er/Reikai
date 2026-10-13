package eu.kanade.tachiyomi.network.interceptor

import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.system.WebViewUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [CloudflareInterceptor.intercept] driven through the real method, with the WebView half faked
 * by name, since it needs a device.
 */
class CloudflareInterceptorTest {

    @Volatile
    private var clearance: String? = null
    private val solves = AtomicInteger()
    private lateinit var interceptor: CloudflareInterceptor

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getMainExecutor(any()) } returns Executor { it.run() }
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk()
        mockkConstructor(Handler::class)
        mockkObject(WebViewUtil)
        every { WebViewUtil.supportsWebView(any()) } returns true

        val jar = mockk<AndroidCookieJar>()
        every { jar.get(any()) } answers {
            val url = firstArg<HttpUrl>()
            listOfNotNull(clearance?.let { Cookie.Builder().name("cf_clearance").value(it).domain(url.host).build() })
        }
        every { jar.remove(any(), any(), any()) } answers {
            clearance = null
            1
        }
        interceptor = spyk(
            CloudflareInterceptor(
                mockk(relaxed = true),
                jar,
                mockk(relaxed = true),
                { "ua" },
                mockk<NetworkPreferences>(relaxed = true) { every { flareSolverrUrl.get() } returns "" },
                // Not in use, so a failed solve falls to the WebView fetch rather than the server.
                mockk { every { isActive() } returns false },
            ),
            recordPrivateCalls = true,
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun solveWith(block: () -> Unit) {
        every { interceptor["solve"](any<Interceptor.Chain>(), any<Request>(), any<Response>()) } answers {
            solves.incrementAndGet()
            block()
            null
        }
    }

    private fun chain(url: String, server: (Request) -> Response = ::server): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        return mockk {
            every { request() } returns request
            every { proceed(any()) } answers { server(firstArg()) }
        }
    }

    // Challenges every request until the host holds a clearance.
    private fun server(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .apply {
            if (clearance == null) {
                code(403).message("Forbidden").header("cf-mitigated", "challenge").header("Server", "cloudflare")
            } else {
                code(200).message("OK")
            }
        }
        .body("".toResponseBody())
        .build()

    private fun runBounded(block: () -> Response): Response? {
        val result = AtomicReference<Response?>()
        val worker = thread(isDaemon = true) { result.set(block()) }
        worker.join(TimeUnit.SECONDS.toMillis(5))
        return result.get()
    }

    @Test
    fun `a challenged request made from within another to the same host does not wait on itself`() {
        solveWith { clearance = "solved" }
        // The outer request is answered only after a nested one to the same host, as an extension's
        // token refresh is, and that nested one is challenged.
        val outer = chain("https://site.example/page") { request ->
            interceptor.intercept(chain("https://site.example/token")).close()
            server(request)
        }

        runBounded { interceptor.intercept(outer) }?.code shouldBe 200
    }

    @Test
    fun `concurrent challenges on one host are solved once`() {
        val queued = CountDownLatch(1)
        solveWith {
            queued.await(1, TimeUnit.SECONDS)
            clearance = "solved"
        }
        val start = CountDownLatch(1)
        val workers = List(8) {
            thread(isDaemon = true) {
                start.await()
                interceptor.intercept(chain("https://site.example/page/$it")).close()
            }
        }
        start.countDown()
        queued.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        solves.get() shouldBe 1
    }

    @Test
    fun `a failed solve releases the host for the next request`() {
        val failures = AtomicInteger()
        every { interceptor["solve"](any<Interceptor.Chain>(), any<Request>(), any<Response>()) } answers {
            solves.incrementAndGet()
            if (failures.getAndIncrement() == 0) throw IOException("bypass failed")
            clearance = "solved"
            null
        }
        runCatching { interceptor.intercept(chain("https://site.example/a")) }

        runBounded { interceptor.intercept(chain("https://site.example/b")) }?.code shouldBe 200
    }
}
