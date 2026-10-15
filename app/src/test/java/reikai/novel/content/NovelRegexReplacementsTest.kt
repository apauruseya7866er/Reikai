package reikai.novel.content

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The compile kernel both the reading pipeline and the rule editor's preview run through, so a rule
 * cannot behave one way when tested and another way when read.
 */
class NovelRegexReplacementsTest {

    private fun rule(
        pattern: String,
        replacement: String = "",
        isRegex: Boolean = true,
        matchWholeWord: Boolean = false,
        caseSensitive: Boolean = false,
    ) = NovelRegexReplacement(
        title = "rule",
        pattern = pattern,
        replacement = replacement,
        isRegex = isRegex,
        matchWholeWord = matchWholeWord,
        caseSensitive = caseSensitive,
    )

    private fun run(rule: NovelRegexReplacement, input: String) =
        NovelRegexReplacements.compile(rule).applyTo(input)

    /** [NovelRegexReplacements.apply] is the pipeline's entry point, and where the empty-chapter guard is. */
    private fun runAll(vararg rules: NovelRegexReplacement, input: String) =
        NovelRegexReplacements.apply(input, NovelRegexRules.encode(rules.toList()))

    @Test
    fun `a regex rule rewrites what it matches`() {
        run(rule("h[0-9]", "p"), "<h1>Title</h1>") shouldBe "<p>Title</p>"
    }

    /** The default, because a reader writing "chapter" should not have to guess the source's casing. */
    @Test
    fun `matching ignores case unless the rule asks for it`() {
        run(rule("chapter", "Part"), "CHAPTER one") shouldBe "Part one"
    }

    @Test
    fun `a case-sensitive rule leaves the other casing alone`() {
        run(rule("chapter", "Part", caseSensitive = true), "CHAPTER one") shouldBe "CHAPTER one"
    }

    /** Without escaping, a find-and-replace of "a.b" would also match "axb". */
    @Test
    fun `a text rule treats its pattern as characters rather than a pattern`() {
        run(rule("a.b", "X", isRegex = false), "axb a.b") shouldBe "axb X"
    }

    @Test
    fun `whole word matching skips a longer word containing it`() {
        run(rule("cat", "dog", isRegex = false, matchWholeWord = true), "cat catalogue") shouldBe "dog catalogue"
    }

    /**
     * The half that separates the two modes. A regex rule may reference its groups, and a text rule
     * may not, so a replacement containing a dollar sign is written out rather than failing the rule.
     */
    @Test
    fun `a regex replacement can reference a captured group`() {
        run(rule("""Chapter (\d+)""", "Ch. $1"), "Chapter 12") shouldBe "Ch. 12"
    }

    @Test
    fun `a text replacement writes a dollar sign out as typed`() {
        run(rule("price", "$5", isRegex = false), "price") shouldBe "$5"
    }

    /** What the editor reports as an error rather than saving a rule that could never run. */
    @Test
    fun `an unparseable pattern throws rather than compiling to nothing`() {
        assertThrows<Exception> { NovelRegexReplacements.compile(rule("[unclosed")) }
    }

    /**
     * The runtime reads a replacement only once something matches, so without this check a rule
     * tested on an empty sample saved cleanly and was then skipped on the first chapter it touched.
     */
    @ParameterizedTest
    @ValueSource(strings = ["$5", "trailing \\", "\${missing}"])
    fun `a regex replacement the runtime cannot read throws at compile`(replacement: String) {
        assertThrows<NovelRegexReplacements.InvalidReplacementException> {
            NovelRegexReplacements.compile(rule("(?<w>a)", replacement))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["$1", "\${w}", "\\$1", "plain"])
    fun `a regex replacement naming a group the pattern has compiles`(replacement: String) {
        run(rule("(?<w>a)", replacement), "b") shouldBe "b"
    }

    @Test
    fun `an empty replacement takes the matched text out`() {
        run(rule("Support me on Patreon!"), "The tale began. Support me on Patreon! It ended.") shouldBe
            "The tale began. It ended."
    }

    /** The whitespace a cut leaves would otherwise double up, which is what makes a cleanup read as a mistake. */
    @Test
    fun `a cut takes the gap around it`() {
        run(rule("AD"), "The crowd was   AD   rowdy") shouldBe "The crowd was rowdy"
    }

    /** A cut between two words would fuse them into one longer, wrong word. */
    @Test
    fun `a cut inside a word leaves a single space`() {
        run(rule("cd"), "abcde") shouldBe "ab e"
    }

    @Test
    fun `a cut never absorbs the full stop before it`() {
        run(rule("AD text"), "End of story. AD text") shouldBe "End of story."
    }

    /** Without this, hiding one injected line would glue its two neighbouring paragraphs together. */
    @Test
    fun `a cut never grows across a newline`() {
        run(rule("AD"), "First.\nAD\nSecond.") shouldBe "First.\nSecond."
    }

    @Test
    fun `a cut that fills its line at the top takes the line break with it`() {
        run(rule("AD"), "AD\nSecond.") shouldBe "Second."
    }

    @Test
    fun `a cut sharing its line with prose leaves the line break alone`() {
        run(rule("AD"), "First.\nAD more\nSecond.") shouldBe "First.\nmore\nSecond."
    }

    /** The guard the whole kernel exists for: showing the junk beats showing a blank chapter. */
    @Test
    fun `a rule that would empty the chapter leaves it alone`() {
        runAll(rule(".*"), input = "A whole chapter of prose.") shouldBe "A whole chapter of prose."
    }

    @Test
    fun `rules that between them would empty the chapter leave it alone`() {
        runAll(rule("[a-m]"), rule("[n-z]"), input = "abcdefghijklmnopqrstuvwxyz") shouldBe "abcdefghijklmnopqrstuvwxyz"
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // What sources actually ship, as one line of a chapter body.
            "Please support me on Patreon at patreon.com/example",
            "Read 40 additional chapters on our site to unlock this one",
            "Updated from freewebnovel.com",
            "Join our discord server for updates",
            "Previous chapter | Next chapter",
        ],
    )
    fun `an injected line is gone and the prose around it stays`(ad: String) {
        val chapter = "He drew his blade. $ad The gate creaked open."
        run(rule("""(?<=\. )${Regex.escape(ad)}"""), chapter) shouldBe "He drew his blade. The gate creaked open."
    }

    @ParameterizedTest
    @ValueSource(strings = ["------", "______", "******", "======", "~~~~~"])
    fun `a separator run between paragraphs is gone`(separator: String) {
        run(rule(Regex.escape(separator)), "First.\n$separator\nSecond.") shouldBe "First.\nSecond."
    }
}
