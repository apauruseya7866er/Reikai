package eu.kanade.tachiyomi.data.track.comick

import eu.kanade.tachiyomi.data.track.comick.dto.ComickLibraryEntry
import eu.kanade.tachiyomi.data.track.comick.dto.ComickLibraryEntryItem
import eu.kanade.tachiyomi.data.track.comick.dto.ComickLibraryEntryProgress
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Comick ids are 8-character strings, stored in the track's numeric remote id. A round trip that loses
 * a bit would bind every later sync to a different title, so the encoding is pinned both ways.
 */
class ComickUtilTest {

    @ParameterizedTest
    @ValueSource(strings = ["abcdEFGH", "AAAAAAAA", "________", "-_09azAZ"])
    fun `a stored id reads back as the same hid`(hid: String) {
        hid.asHidToLong().toHid() shouldBe hid
    }

    @Test
    fun `an id of the wrong length is refused`() {
        shouldThrow<IllegalArgumentException> { "abc".asHidToLong() }
    }

    @ParameterizedTest
    @ValueSource(longs = [Comick.READING, Comick.COMPLETED, Comick.ON_HOLD, Comick.DROPPED, Comick.PLAN_TO_READ])
    fun `every offered status survives the trip to the service and back`(status: Long) {
        status.toApiListStatus().toLong().fromApiListStatus() shouldBe status
    }

    @Test
    fun `a library entry with no progress reads as nothing read`() {
        val entry = ComickLibraryEntry(
            ComickLibraryEntryItem(
                "abcdEFGH",
                "Title",
                status = 1,
                progress = ComickLibraryEntryProgress(null),
                rating = null,
            ),
        )

        entry.toTrack(12L).last_chapter_read shouldBe 0.0
    }
}
