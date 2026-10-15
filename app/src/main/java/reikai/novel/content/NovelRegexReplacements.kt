package reikai.novel.content

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Collections
import java.util.LinkedHashMap

object NovelRegexReplacements {

    /**
     * A rule ready to run. [literal] is the difference between the two modes: a regex rule's
     * replacement may reference groups with `$1`, while a find-and-replace-text rule's is taken
     * exactly as typed, so a price like `$5` does not read as a group and fail the whole rule.
     */
    data class Compiled(val regex: Regex, val replacement: String, val literal: Boolean) {
        /** An empty replacement takes text out of the chapter rather than rewriting it. */
        val removes: Boolean get() = replacement.isEmpty()

        fun applyTo(input: String): String = when {
            removes -> input.withoutRanges(regex)
            literal -> regex.replace(input) { replacement }
            else -> regex.replace(input, replacement)
        }
    }

    /** The pattern compiled but its replacement cannot be read, so the editor marks the Replace field. */
    class InvalidReplacementException(cause: Exception) : IllegalArgumentException(cause.message, cause)

    // Compiling a rule set costs more than running it and the same set is re-applied to every
    // chapter, so the compiled form is cached against the raw JSON. Access-ordered and capped, so
    // editing rules mid-session cannot grow it without bound.
    private val cache: MutableMap<String, List<Compiled>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<Compiled>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<Compiled>>,
                ) = size > 16
            },
        )

    /**
     * Compiles one rule. Throws whatever the pattern failed with, or [InvalidReplacementException] for
     * a regex replacement, so the editor can name the problem; the pipeline catches and skips the
     * rule instead. Both go through here, or a rule could behave one way when tested and another way
     * when read.
     */
    fun compile(rule: NovelRegexReplacement): Compiled {
        val regex = findRegex(rule.pattern, rule.isRegex, rule.matchWholeWord, rule.caseSensitive)
        if (rule.isRegex) checkReplacement(regex, rule.replacement)
        return Compiled(regex, rule.replacement, literal = !rule.isRegex)
    }

    /**
     * What a find matches: [pattern] as a regex, or else literally and, with [wholeWord], only as a whole
     * word. A regex says that itself, so [wholeWord] is ignored for one, as the rule editor hides it.
     * Throws whatever an invalid regex fails with. The rules and the chapter text search both find here.
     */
    fun findRegex(pattern: String, isRegex: Boolean, wholeWord: Boolean, caseSensitive: Boolean): Regex {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        if (isRegex) return Regex(pattern, options)
        val escaped = Regex.escape(pattern)
        val bounded = if (wholeWord) "(?<![\\p{L}\\p{N}_])(?:$escaped)(?![\\p{L}\\p{N}_])" else escaped
        return Regex(bounded, options)
    }

    /**
     * A shipped rule rather than a saved one. Multiline is on so its `^` and `$` are line anchors, the
     * same as Inkbound's, which a rule whose pattern is authored against chapter markup needs.
     */
    fun builtin(pattern: String): Compiled = Compiled(
        regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        replacement = "",
        literal = false,
    )

    /** [shipped] runs first, so a rule the reader wrote matches what the shipped rules left behind. */
    fun apply(content: String, rulesJson: String, shipped: List<Compiled> = emptyList()): String {
        if (rulesJson.isBlank() ||
            rulesJson == "[]"
        ) {
            return if (shipped.isEmpty()) content else runAll(content, shipped)
        }

        val compiled = synchronized(cache) {
            cache.computeIfAbsent(rulesJson) { json ->
                NovelRegexRules.decode(json).mapNotNull { rule ->
                    if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                    try {
                        compile(rule)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Failed to compile regex for '${rule.title}'" }
                        null
                    }
                }
            }
        }

        return runAll(content, shipped + compiled)
    }

    private fun runAll(content: String, compiled: List<Compiled>): String {
        var result = content
        for (rule in compiled) {
            try {
                result = rule.applyTo(result)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Regex replacement failed" }
            }
        }
        // A rule that matches everything leaves a blank chapter, which reads as a broken download
        // rather than as a cleanup. Showing the junk beats showing nothing.
        return if (result.any { it.isLetterOrDigit() }) result else content
    }

/** Punctuation that trails a removed line, so a cut takes it rather than leaving it dangling. */
    private const val EDGE_SEPARATORS = ",;:–—|/·-"

    /** Spaces [Char.isWhitespace] calls false: the non-breaking ones, and the zero-width space scrapers use to split a domain. */
    private val EXTRA_SPACES = intArrayOf(0x00A0, 0x2007, 0x202F, 0x200B)

    /**
     * A newline is never absorbed, so hiding one injected line cannot glue the paragraphs either side
     * of it, and a sentence terminator never is, so hiding a line keeps the chapter's full stops.
     */
    private fun Char.isCutSeparator(): Boolean = when {
        this == '\n' || this == '\r' -> false
        this.code in EXTRA_SPACES -> true
        this.isWhitespace() -> true
        else -> this in EDGE_SEPARATORS
    }

    /**
     * Takes every match out as a range rather than by repeated replacement, so the gaps merge and a
     * word either side of a cut is not fused into one longer word.
     */
    private fun String.withoutRanges(regex: Regex): String {
        val cuts = regex.findAll(this).map { it.range.first to it.range.last + 1 }.sortedBy { it.first }.toList()
        if (cuts.isEmpty()) return this

        val out = StringBuilder(length)
        var cursor = 0
        var lastKept = ' '
        var justCut = false

        fun keep(from: Int, to: Int) {
            if (to <= from) return
            if (justCut && !lastKept.isWhitespace() && this[from].isLetterOrDigit()) out.append(' ')
            out.append(this, from, to)
            lastKept = this[to - 1]
            justCut = false
        }

        for (cut in mergeCuts(cuts)) {
            keep(cursor, minOf(cut.first, length))
            cursor = maxOf(cut.second, cursor)
            justCut = true
        }
        keep(cursor, length)
        return out.toString()
    }

    /** A line holding nothing but the cut gives its line break up too, so hiding one leaves no gap. */
    private fun String.isBlankBefore(index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && this[i] != '\n') {
            if (!this[i].isWhitespace()) return false
            i--
        }
        return true
    }

    private fun String.isBlankAfter(index: Int): Boolean {
        var i = index
        while (i < length && this[i] != '\n') {
            if (!this[i].isWhitespace()) return false
            i++
        }
        return true
    }

    /** Merges overlaps, then grows each over separators, merging again since growing can make two meet. */
    private fun String.mergeCuts(sorted: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val merged = ArrayList<Pair<Int, Int>>()
        for (cut in sorted) {
            val last = merged.lastOrNull()
            if (last != null && cut.first <= last.second) {
                if (cut.second > last.second) merged[merged.size - 1] = last.first to cut.second
            } else {
                merged.add(cut)
            }
        }

        val grown = ArrayList<Pair<Int, Int>>()
        for (cut in merged) {
            var start = cut.first
            var end = cut.second
            while (start > 0 && this[start - 1].isCutSeparator()) start--
            while (end < length && this[end].isCutSeparator()) end++
            if (isBlankBefore(start) && isBlankAfter(end)) {
                when {
                    start > 0 && this[start - 1] == '\n' -> start--
                    end < length && this[end] == '\n' -> end++
                }
            }
            val last = grown.lastOrNull()
            if (last != null && start <= last.second) {
                if (end > last.second) grown[grown.size - 1] = last.first to end
            } else {
                grown.add(start to end)
            }
        }
        return grown
    }

    private val groupNameRegex = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")

    /**
     * The runtime reads a replacement only when something matches, so it is run once against a
     * stand-in pattern with the same groups that matches the empty string. The runtime judges its own
     * grammar that way. The name scan can only over-find (a name inside a character class), which
     * accepts a rule the reader then skips, never refuses a valid one.
     */
    private fun checkReplacement(regex: Regex, replacement: String) {
        val names = groupNameRegex.findAll(regex.pattern).map { it.groupValues[1] }.distinct().toList()
        val unnamed = (regex.toPattern().matcher("").groupCount() - names.size).coerceAtLeast(0)
        val standIn = names.joinToString("") { "(?<$it>)" } + "()".repeat(unnamed)
        try {
            Regex(standIn).replace("", replacement)
        } catch (e: RuntimeException) {
            // An unknown group number throws IndexOutOfBoundsException, the rest IllegalArgumentException.
            throw InvalidReplacementException(e)
        }
    }
}
