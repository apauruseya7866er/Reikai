package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The shipped cleanup rules, checked against the lines sources actually inject and against prose that
 * must survive them. Most cases are the shape a chapter arrives in: markup on one line, with no
 * breaks in it, because a source is free to send that and a rule anchored to lines would miss it.
 */
class BuiltinCleanupRulesTest {

    private fun clean(html: String, disabled: Set<String> = emptySet()): String =
        NovelRegexReplacements.apply(html, "[]", BuiltinCleanupRules.active(disabled))

    @Test
    fun `every shipped rule compiles`() {
        BuiltinCleanupRules.ALL.forEach { NovelRegexReplacements.builtin(it.pattern) }
    }

    @Test
    fun `shipped rule ids are unique`() {
        BuiltinCleanupRules.ALL.map { it.id }.toSet().size shouldBe BuiltinCleanupRules.ALL.size
    }

    @Test
    fun `a rule the reader turned off is not in the active set`() {
        BuiltinCleanupRules.active(setOf("builtin_patreon")).size shouldBe BuiltinCleanupRules.ALL.size - 1
    }

    @ParameterizedTest
    @MethodSource("junk")
    fun `a junk paragraph in markup is gone and the prose around it stays`(ad: String) {
        clean("<p>He drew his blade.</p><p>$ad</p><p>The gate creaked open.</p>") shouldBe
            "<p>He drew his blade.</p><p>The gate creaked open.</p>"
    }

    @ParameterizedTest
    @MethodSource("junk")
    fun `the same junk in plain text with line breaks is gone`(ad: String) {
        clean("He drew his blade.\n$ad\nThe gate creaked open.") shouldBe "He drew his blade.\nThe gate creaked open."
    }

    @ParameterizedTest
    @MethodSource("prose")
    fun `prose that reads like an appeal survives`(line: String) {
        // Siblings on both sides, or the empty-chapter guard would report a pass for a rule that did
        // match: the line alone would be rescued rather than kept.
        clean("<p>He drew his blade.</p><p>$line</p><p>The gate creaked open.</p>") shouldBe
            "<p>He drew his blade.</p><p>$line</p><p>The gate creaked open.</p>"
    }

    /** The one shape no paragraph anchor can reach, which is why there is a sentence rule at all. */
    @Test
    fun `an appeal sharing a paragraph with prose takes only itself`() {
        clean("<p>The gate creaked open. Please support me. He drew his blade.</p>") shouldBe
            "<p>The gate creaked open. He drew his blade.</p>"
    }

    @Test
    fun `a rule the reader turned off leaves its junk alone`() {
        val chapter = "<p>He drew his blade.</p><p>Support me on Patreon.</p><p>The gate creaked open.</p>"
        clean(chapter, setOf("builtin_patreon", "builtin_support_me")) shouldBe chapter
    }

    @Test
    fun `a chapter that is nothing but markup survives untouched`() {
        clean("<p></p><p><br></p>") shouldBe "<p></p><p><br></p>"
    }

    @Test
    fun `a chapter that is nothing but junk is left rather than blanked`() {
        val chapter = "<p>Support me on Patreon.</p>"
        clean(chapter) shouldBe chapter
    }

    companion object {

        /** What sources ship, gathered from live chapter pages rather than invented. */
        @JvmStatic
        fun junk(): List<String> = listOf(
            "Please support me on Patreon at patreon.com/example",
            "Read 30 Chapters In Advance: patreon.com/Albino1",
            "Read 40 additional chapters on our site to unlock this one",
            "Read 100+ advanced chapters on Patreon. If you'd like to support me, check it out.",
            "Unlock exclusive access to advanced chapters: patreon.com/example",
            "You can read advance chapters and view extra scenes on pat reon.com/example",
            "Read Complete Book At PA Treon p@treon.com/example",
            "Support Me At Pat Reon.... Read Advanced Chapters here",
            "Support my Patreon (someone), which is ahead and has free chapters.",
            "Thank you for the support, friends. If you want to read more chapters in advance, go to my Patreon.",
            "Join our discord server for updates",
            "discord.gg/abc123",
            "Previous chapter | Next chapter",
            "Please read the next chapter to continue the story",
            "Click here to read the next chapter",
            "Chapter 4 of 480",
            "Thanks for reading!",
            "Please vote for this novel on top novels",
            "Check out my other novels!",
            "If you enjoyed this novel, consider reading my other stories",
            "Translation by Someone",
            "Editor: Someone",
            "T/N: Someone",
            "TL - Someone",
            "Updated from freewebnovel.com",
            "updated from f r e e w e b n o v e l",
            "novelfull.com",
            "https://example.com/novel/chapter-1",
            "www.example.com",
            "translator@example.com",
            "---------------",
            "______________",
            "**************",
            "========================",
        )

        /** Prose a reader would be angry to lose. */
        @JvmStatic
        fun prose(): List<String> = listOf(
            "He drew his blade and the crowd went silent.",
            "The support troops arrived before dawn.",
            "She tipped the waiter and left.",
            "They joined the guild at sixteen.",
            "Our community of readers meets on the first of the month.",
            "He voted for the proposal and it passed.",
            "She reviewed the manuscript twice.",
            "The chapter was long, but she read it twice.",
            "Rate the guard at three in the morning.",
            "Please read the sign before you cross.",
            "He typed donate into the search bar and found nothing.",
            "The translator explained the choice of word in her note.",
            "This chapter is the long one everyone dreads.",
            "Three ships joined the line before sunrise.",
            "My Patreon is a place where I post sketches.",
            "He tipped his hat and walked on.",
            "Prose that merely mentions support or a vote is not an appeal.",
        )
    }
}
