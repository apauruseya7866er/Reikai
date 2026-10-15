package reikai.novel.content

/**
 * One shipped cleanup rule, kept in code rather than in the reader's saved rules so a fix to a
 * pattern reaches everyone who has not turned it off. Turning it off stores its id, which is why the
 * id is part of the stored state and the pattern is not.
 */
data class CleanupRule(val id: String, val pattern: String)

/**
 * The junk novel sources inject into chapter text. Each rule anchors after a tag or a line break,
 * which is where a paragraph begins in markup and where a line begins in text, and takes the
 * paragraph's own tags with it so a paragraph that is entirely junk leaves nothing behind. A line
 * anchor would miss every ad on a site that ships a chapter as one unbroken line. The reasoning and
 * the corpus behind each rule: docs/dev/plans/novel-text-cleanup.md.
 */
object BuiltinCleanupRules {

    /** Where a paragraph or a line starts, taking the opening tag with it when the text is markup. */
    private const val PARAGRAPH_START = "(?:<p[^>]*>|<br[^>]*>|(?<=[\\n>])|^)"

    /** Where a junk sentence starts inside a paragraph, which a whole-paragraph anchor would miss. */
    private const val SENTENCE_START = "(?<=[.!?…]\\s)"

    /** To the closing tag of the same paragraph, or to the end of the line, whichever comes first. */
    private const val PARAGRAPH_END = "[^<\\n]*(?:</p>|<br[^>]*>)?"

    /** Spelled out because sources write it every way at once: patreon, p@treon, pat reon, PA Treon. */
    private const val PATREON = "(?:pat[\\s-]?reon|a@treon|p\\s?a\\s?treon)"

    /**
     * A second word, because a novel in which a character mentions Patreon is not an ad. Every line
     * the sources ship pairs the platform with a sales word or a link.
     */
    private const val MARKETING =
        "(?:support|donat|subscribe|early access|advance[d]? chapters?|extra chapters|more chapters" +
            "|new chapters|unreleased|exclusive|members?|check out|sign ?up|link|www\\.|https?://|\\.com)"

    private const val TOP_LEVEL_DOMAIN =
        "(?:com|net|org|io|gg|me|ru|xyz|top|shop|info|co|cc|tv|link|site|online|mobi|ink|pro|vip)"

    val ALL: List<CleanupRule> = listOf(
        CleanupRule(
            id = "builtin_patreon",
            pattern = "$PARAGRAPH_START(?=[^<\\n]*(?:$PATREON))(?=[^<\\n]*$MARKETING)$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_support_me",
            pattern = "$PARAGRAPH_START(?:(?:please\\s+)?(?:support|donate|tip)\\s+(?:me|us|my|our)\\b" +
                "|ko-?fi|paypal\\.me|cash\\.app|buymeacoffee)$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_community",
            pattern = "$PARAGRAPH_START(?:join\\s+(?:us\\s+)?(?:on\\s+)?(?:my|our|the)\\s+" +
                "(?:discord|server|community|telegram|group|channel)|discord\\.(?:gg|com|app|me|io)\\b" +
                "|(?:follow|subscribe)\\s+(?:me|us)\\s+on\\b)$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_advanced_chapters",
            pattern = "$PARAGRAPH_START(?:read\\s+\\d+\\+?\\s+(?:additional\\s+|more\\s+|advanced\\s+)?" +
                "chapters?|\\d+\\s+chapters?\\s+in\\s+advance|unlock\\s+(?:exclusive|early)\\s+access" +
                "|early\\s+access\\s+to\\s+(?:advanced\\s+)?chapters?)$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_chapter_footer",
            pattern = "$PARAGRAPH_START(?:(?:please\\s+)?(?:read|continue)\\s+" +
                "(?:the\\s+|next\\s+|n[°o]\\.?\\s*\\d*\\s*)?(?:next\\s+)?chapter\\b" +
                "|(?:previous|prev|last|next)\\s+chapter\\b|(?:click|tap)\\s+(?:here|below)\\b" +
                "|chapter\\s+\\d+\\s+(?:of|/)\\s+\\d+)$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_thanks_reading",
            pattern = "$PARAGRAPH_START(?:thank you|thanks)\\s+for\\s+(?:reading|the support)\\b$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_voting",
            pattern = "$PARAGRAPH_START(?:please\\s+)?(?:vote|rate|review)\\s+(?:for|on)\\b$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_novel_promo",
            pattern = "$PARAGRAPH_START(?:(?:check out|read|visit)\\s+(?:my|our)\\s+(?:other\\s+)?" +
                "(?:novels?|stories|books?|series|websites?|twitch)|if\\s+you\\s+" +
                "(?:enjoyed|liked|love|finished|read|completed)\\s+this\\s+novel)$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_translation_credits",
            pattern = "$PARAGRAPH_START(?:(?:translation|translator|translated|editing|edited|editor" +
                "|proofread(?:er|ers|ing)?|typeset(?:ter|ting)?|illustrat(?:or|ors|ion|ions)?|raw)" +
                "\\s*(?:by\\b|[:\\-])|(?:T\\s*/?\\s*[NL]|ED|EN|P\\s*/?\\s*R)\\s*[:\\-])$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_watermark",
            pattern = "$PARAGRAPH_START(?:updated\\s+from\\s+|translated\\s+by\\s+re\\s+)?" +
                "(?:f\\s*r\\s*e\\s*e\\s*w\\s*e\\s*b\\s*n\\s*o\\s*v\\s*e\\s*l|novelfull|novelbin" +
                "|mnovelfull|webnovel|lightnovel|readnovelfull)$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_separator",
            pattern = "$PARAGRAPH_START[-_=~*#•·+]{3,}$PARAGRAPH_END",
        ),
        CleanupRule(
            id = "builtin_bare_link",
            pattern = "$PARAGRAPH_START(?:(?:https?://|www\\.)?[^<\\s]+\\.$TOP_LEVEL_DOMAIN\\b" +
                "|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+)$PARAGRAPH_END",
        ),
        // The one rule that runs mid-paragraph: a source that appends its appeal to a real sentence
        // leaves no paragraph of its own, and a sentence anchor is the only place it can be cut.
        CleanupRule(
            id = "builtin_support_sentence",
            pattern = "$SENTENCE_START(?:please\\s+)?(?:support|donate)\\s+(?:me|us)\\b[^.!?<\\n]*[.!?]",
        ),
    )

    private val compiled = HashMap<String, NovelRegexReplacements.Compiled>()

    /** The rules to run, in order. Cached, since every chapter load compiles the same set. */
    fun active(disabledIds: Set<String>): List<NovelRegexReplacements.Compiled> = synchronized(compiled) {
        ALL.filter { it.id !in disabledIds }.map { rule ->
            compiled.getOrPut(rule.id) { NovelRegexReplacements.builtin(rule.pattern) }
        }
    }
}
