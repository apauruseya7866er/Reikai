package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.util.system.isOnline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.track.TrackerSignedOutException
import reikai.presentation.track.trackerErrorMessage
import java.net.SocketTimeoutException

/** A browser sign-in that fails: a refusal signs out and says why, anything else leaves the session alone. */
class CookieLoginFailureTest {

    private val context = mockk<Context>()
    private val tracker = mockk<Tracker>(relaxed = true, moreInterfaces = arrayOf(CookieLoginTracker::class)) {
        every { name } returns "Novel Updates"
    }

    @BeforeEach
    fun setUp() {
        mockkStatic(TRACKER_ERROR, NETWORK)
        every { any<Context>().trackerErrorMessage(any(), any()) } answers { "Log in to ${secondArg<String>()} again" }
        every { any<Context>().isOnline() } returns true
    }

    @AfterEach
    fun tearDown() = unmockkStatic(TRACKER_ERROR, NETWORK)

    @Test
    fun `a refused sign-in is told in the shared tracker wording, not the raw exception`() = runTest {
        val cookieLogin = mockk<CookieLoginTracker> {
            coEvery { loginWithCookie(any()) } throws TrackerSignedOutException("Novel Updates")
        }

        context.cookieLoginFailure(tracker, cookieLogin, "session=x") shouldBe "Log in to Novel Updates again"
    }

    @Test
    fun `a refused sign-in signs the tracker back out`() = runTest {
        context.cookieLoginFailure(tracker, failingWith(TrackerSignedOutException("Novel Updates")), "session=x")

        verify { tracker.logout() }
    }

    @Test
    fun `a check that times out leaves the site session alone`() = runTest {
        context.cookieLoginFailure(tracker, failingWith(SocketTimeoutException()), "session=x")

        verify(exactly = 0) { tracker.logout() }
    }

    @Test
    fun `a closed screen cancels the sign-in instead of reporting it`() = runTest {
        shouldThrow<CancellationException> {
            context.cookieLoginFailure(tracker, failingWith(CancellationException()), "session=x")
        }
    }

    @Test
    fun `a closed screen leaves the site session alone`() = runTest {
        runCatching { context.cookieLoginFailure(tracker, failingWith(CancellationException()), "session=x") }

        verify(exactly = 0) { tracker.logout() }
    }

    @Test
    fun `a password tracker's failed sign-in still signs out, as upstream does`() {
        val passwordTracker = mockk<Tracker>(relaxed = true)

        context.signOutAfterFailedLogin(passwordTracker, SocketTimeoutException())

        verify { passwordTracker.logout() }
    }

    private fun failingWith(error: Throwable) = mockk<CookieLoginTracker> {
        coEvery { loginWithCookie(any()) } throws error
    }

    private companion object {
        const val TRACKER_ERROR = "reikai.presentation.track.TrackerErrorMessageKt"
        const val NETWORK = "eu.kanade.tachiyomi.util.system.NetworkExtensionsKt"
    }
}
