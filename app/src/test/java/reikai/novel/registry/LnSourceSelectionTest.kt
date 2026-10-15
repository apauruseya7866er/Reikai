package reikai.novel.registry

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What a fresh source picker shows and ticks, which is the whole default the reader meets. */
class LnSourceSelectionTest {

    private fun entry(id: String, lang: String) = LnRegistryEntry(
        id = id,
        name = id,
        version = "1.0.0",
        site = "https://example.com",
        lang = lang,
        url = "https://example.com/$id.js",
    )

    private val registry = listOf(
        entry("novel-en-1", "English"),
        entry("novel-en-2", "English"),
        entry("novel-fr", "Français"),
        entry("novel-es", "Español"),
        entry("novel-multi", "Multi"),
    )

    @Test
    fun `a fresh picker opens on English`() {
        languagesOf(registry).first() shouldBe DEFAULT_SOURCE_LANGUAGE
    }

    @Test
    fun `the rest of the languages follow English alphabetically`() {
        languagesOf(registry) shouldBe listOf("en", "all", "es", "fr")
    }

    /** The registry names each language in that language, so the filter has to translate before comparing. */
    @Test
    fun `a language filter is matched on the code, not on the name the registry used`() {
        entriesIn(registry, "fr").map { it.id } shouldBe listOf("novel-fr")
    }

    @Test
    fun `no language filter offers every entry`() {
        entriesIn(registry, null) shouldBe registry
    }

    @Test
    fun `a fresh picker has the English sources ticked and nothing else`() {
        defaultSelection(registry) shouldBe setOf("novel-en-1", "novel-en-2")
    }

    @Test
    fun `a registry with no English source starts with nothing ticked`() {
        defaultSelection(listOf(entry("only-fr", "Français"))) shouldBe emptySet()
    }

    @Test
    fun `one repo's registry is offered, and an unreachable one costs nothing`() {
        val results = mapOf(
            LNREADER_PLUGIN_REGISTRY to LnRepoResult.Reached(registry),
            "https://example.com/broken.json" to LnRepoResult.Unreachable("offline"),
        )

        installableEntries(results).map { it.id } shouldBe registry.map { it.id }
    }

    /** A source listed twice by two repos is one row, not two the reader would install twice. */
    @Test
    fun `a source two repos list is offered once`() {
        val results = mapOf(
            "a" to LnRepoResult.Reached(listOf(entry("same", "English"))),
            "b" to LnRepoResult.Reached(listOf(entry("same", "English"))),
        )

        installableEntries(results).size shouldBe 1
    }

    @Test
    fun `an entry with no script URL is not offered, since there is nothing to install`() {
        val results = mapOf(
            LNREADER_PLUGIN_REGISTRY to LnRepoResult.Reached(
                listOf(entry("no-script", "English")).map { it.copy(url = "") },
            ),
        )

        installableEntries(results) shouldBe emptyList()
    }

    @Test
    fun `nothing is reported as unreadable when one repo answered`() {
        val results = mapOf(
            "a" to LnRepoResult.Reached(registry),
            "b" to LnRepoResult.Unreachable("offline"),
        )

        unreadableMessage(results) shouldBe null
    }

    @Test
    fun `the reason every repo failed is what the picker reports`() {
        val results = mapOf("b" to LnRepoResult.Unreachable("offline"))

        unreadableMessage(results) shouldBe "offline"
    }

    @Test
    fun `no repo at all is not a failure to report`() {
        unreadableMessage(emptyMap()) shouldBe null
    }
}
