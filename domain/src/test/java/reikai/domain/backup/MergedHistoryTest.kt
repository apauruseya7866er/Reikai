package reikai.domain.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class MergedHistoryTest {

    @Test
    fun `a chapter with no stored row takes the incoming read and time`() {
        mergedHistory(readAt = at(500), readDuration = 60, storedReadAt = null, storedDuration = 0) shouldBe
            (at(500) to 60L)
    }

    @Test
    fun `a row already holding the incoming read gains no time`() {
        mergedHistory(readAt = at(500), readDuration = 60, storedReadAt = at(500), storedDuration = 60) shouldBe
            (at(500) to 0L)
    }

    @Test
    fun `a later stored read is kept while the longer incoming time tops the row up`() {
        mergedHistory(readAt = at(500), readDuration = 60, storedReadAt = at(900), storedDuration = 20) shouldBe
            (at(900) to 40L)
    }

    @Test
    fun `a later incoming read moves the row forward without adding time it already has`() {
        mergedHistory(readAt = at(900), readDuration = 20, storedReadAt = at(500), storedDuration = 60) shouldBe
            (at(900) to 0L)
    }

    @Test
    fun `a removed incoming read keeps the stored time`() {
        mergedHistory(readAt = null, readDuration = 5, storedReadAt = at(500), storedDuration = 5) shouldBe
            (at(500) to 0L)
    }

    @Test
    fun `a row removed on both sides stays removed`() {
        mergedHistory(readAt = null, readDuration = 5, storedReadAt = null, storedDuration = 5) shouldBe (null to 0L)
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis)
}
