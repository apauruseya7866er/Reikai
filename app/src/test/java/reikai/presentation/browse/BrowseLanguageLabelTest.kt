package reikai.presentation.browse

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** Android reads an empty tag as the device's own language, which a heading must never name. */
class BrowseLanguageLabelTest {

    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(MR.strings.unknown) } returns "Unknown"
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `the section of sources declaring no language is headed unknown`() {
        browseLanguageLabel("", context) shouldBe "Unknown"
    }

    @Test
    fun `a declared language still heads its section by its own name`() {
        browseLanguageLabel("fr", context) shouldBe "Français"
    }
}
