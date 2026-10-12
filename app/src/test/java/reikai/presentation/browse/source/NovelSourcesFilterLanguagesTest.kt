package reikai.presentation.browse.source

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelSource

/** The novel filter screen offers a switch for every language a listing offers, installed or not. */
class NovelSourcesFilterLanguagesTest {

    @Test
    fun `a language only a listing offers gets a switch with no sources under it`() {
        filterLanguages(listOf(EN_SOURCE), listed = listOf("ja")) shouldBe
            listOf("en" to listOf(EN_SOURCE), "ja" to emptyList())
    }

    @Test
    fun `a listing's language name joins the installed sources of its code`() {
        filterLanguages(listOf(EN_SOURCE), listed = listOf("English")) shouldBe listOf("en" to listOf(EN_SOURCE))
    }

    private companion object {
        val EN_SOURCE = mockk<NovelSource> {
            every { id } returns "plugin:en"
            every { lang } returns "en"
            every { name } returns "Site"
        }
    }
}
