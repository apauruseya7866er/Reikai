package reikai.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

/** A tapped recommendation opens a local manga, except a tracker's, which no installed source owns. */
class RelatedMangaLocalIdTest {

    // Answers only the candidate's own url under its own source, so a wrong conversion fails the call.
    private val networkToLocalManga = mockk<NetworkToLocalManga> {
        coEvery { this@mockk.invoke(match<Manga> { it.source == 1L && it.url == "/m" }) } answers
            { firstArg<Manga>().copy(id = 42L) }
    }

    @Test
    fun `a source's candidate resolves to its local id`() = runTest {
        networkToLocalManga.localIdOf(candidate(sourceId = 1L)) shouldBe 42L
    }

    @Test
    fun `a tracker's candidate resolves to nothing`() = runTest {
        networkToLocalManga.localIdOf(candidate(sourceId = RECOMMENDS_SOURCE)) shouldBe null
    }

    private fun candidate(sourceId: Long) = RelatedMangaCandidate(
        sourceId = sourceId,
        manga = SManga.create().apply {
            url = "/m"
            title = "m"
        },
        origin = RecommendationOrigin.SourceNative("source"),
    )
}
