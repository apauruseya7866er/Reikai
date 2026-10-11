package reikai.domain.library

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** The re-predict and keep-user-interval rules manga's `FetchInterval` and the novel prediction both call. */
class ReleaseIntervalTest {

    private val zone = TimeZone.UTC
    private val window = at(1_000L)..at(2_000L)

    @Test
    fun `an unchanged list with a prediction inside the window is not predicted again`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, at(1_500L), window) shouldBe false
    }

    @Test
    fun `a refresh asked for by hand predicts again`() {
        ReleaseInterval.needsPrediction(manualFetch = true, fetchInterval = 3, nextUpdate = at(1_500L), window) shouldBe
            true
    }

    @Test
    fun `an entry never predicted is predicted`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 0, at(1_500L), window) shouldBe true
    }

    @Test
    fun `a prediction fallen behind the window is predicted again`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, nextUpdate = at(999L), window) shouldBe
            true
    }

    @Test
    fun `a missing prediction is predicted when there is a window`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, nextUpdate = null, window) shouldBe
            true
    }

    @Test
    fun `without a window only a hand refresh or a new entry predicts`() {
        ReleaseInterval.needsPrediction(manualFetch = false, fetchInterval = 3, null, window = null) shouldBe false
    }

    @Test
    fun `an interval the user set is kept`() {
        ReleaseInterval.userOrPredicted(-5) { 2 } shouldBe -5
    }

    @Test
    fun `an interval nobody set is predicted`() {
        ReleaseInterval.userOrPredicted(3) { 2 } shouldBe 2
    }

    @Test
    fun `no window means today's`() {
        val today = LocalDate(2026, 9, 17)

        ReleaseInterval.windowOrToday(null, today, zone) shouldBe ReleaseInterval.window(today, zone)
    }

    @Test
    fun `a given window is kept`() {
        ReleaseInterval.windowOrToday(window, LocalDate(2026, 9, 17), zone) shouldBe window
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)
}
