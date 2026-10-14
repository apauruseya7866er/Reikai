# Novel text cleanup

## Goal

Long-press a sentence in the novel reader to hide it, everywhere and permanently, with a settings page that lists every hidden and built-in rule and shows how much each one has matched. Injected junk stops appearing on the page and in read-aloud at once, and no rule can silently delete a chapter.

## Why

Novel sources inject donation pleas, Patreon and Discord links, chapter footers, translation credits, bare URLs, emails, watermark lines and separator runs such as `------`, `______` and `******`. Some append the junk to a real paragraph instead of giving it a line of its own, and some obfuscate it (`pat reon.com`, `p@treon`, `Read 30 Chapters In Advance:`).

Reikai already runs every chapter through `NovelContentPipeline.process` before either renderer sees it, and read-aloud asks the renderer for its paragraph list, so a rule applied in the pipeline is unspokable by construction. The gaps are three: Reikai ships only user-authored find-and-replace rules, whose `apply` is a bare `replace` loop with no separator handling and no whole-chapter safety net; it has no built-in rules at all; and its long press is unused outside the opt-in text-selection setting, so the natural place for a hide gesture is already empty.

Inkbound (the LNReader fork at `Zangetsu`) ships this as a separate filter engine over its own token stream, which obliges it to maintain per-token offset arrays so the page and the voice agree on what survived. Reikai does not have that problem and must not import that machinery.

## Approach

A rule matches a span of chapter text. The span is cut out together with the whitespace around it, and what remains is what the page shows and the voice reads, because both read the pipeline's output.

**Range-based deletion.** `NovelRegexReplacements.apply` stops being a loop of `replace` calls and becomes: find every match of every enabled rule, merge overlapping and touching ranges, then grow each over separators. A separator is whitespace plus the characters Inkbound lists as `_edgeSeps`: comma, semicolon, colon, en dash, em dash, pipe, slash, middle dot and hyphen. A newline is never a separator, so one hidden ad cannot swallow the paragraph break and glue the paragraphs either side of it. `.`, `!` and `?` are never separators either, because absorbing one takes the chapter's final full stop. When a cut lands between two word characters, exactly one space is put back. When the rules would empty the chapter, the input is returned untouched.

**Built-ins as code with an id denylist.** `BuiltinCleanupRules` holds the rules as stable ids. A user who dislikes one stores its id in a disabled set, which is a denylist rather than a deletion, so a later fix to a pattern reaches everyone who did not turn it off. Built-ins render in the same list, through the same editor and the same live preview as user rules, and a built-in can be forked into a personal copy rather than edited.

**Long press resolves a sentence and hides it.** The character offset under the finger comes from the same `getLineForVertical` plus `getOffsetForHorizontal` pair `LinkOnlyMovementMethod.linksAt` already performs and discards. That offset is expanded to the sentence covering it, falling back to the whole block when nothing covers it, which is the only way a heading or an unpunctuated line is reachable. The rule written is the sentence text escaped and word-bounded, `(?<![\w])…(?![\w])`, not `^…$`: injected text frequently arrives as two sentences on one line, where a line-anchored rule silently matches nothing.

**Both renderers, one gesture.** The native path adds `onLongPress` to the `selectableTaps` detector the viewport already feeds. The WebView path adds one `@JavascriptInterface onLongPress(x, y)` on `NovelWebBridge`, armed from the elapsed time `reader.js` already computes on touch end, and resolves through its existing node-to-offset mapping. One `TextViewportContractTest` body covers both, since that test is parameterized over the renderers.

**Feedback.** Each rule records how many chapters it matched and the last series it was seen in, shown in the settings list, so an over-broad rule is visible before it eats a chapter.

## Key files

- `reikai/novel/content/NovelRegexReplacements.kt`, `apply` and `findRegex`: becomes the range kernel. `Compiled` gains the match-range form.
- `reikai/novel/content/NovelRegexReplacement.kt`, `NovelRegexReplacement` and `NovelRegexRules`: the stored rule and its codec, reused unchanged for hidden sentences.
- `reikai/novel/content/NovelContentConfig.kt`, `from`: must name every preference `from` reads, and `NovelContentConfigTest` compares the two, so the new master switch and disabled-id set are declared here.
- `reikai/novel/content/NovelContentPipeline.kt`, `process`: the single call site, so both renderers and read-aloud inherit the change.
- `reikai/presentation/reader/text/LinkOnlyMovementMethod.kt`, `linksAt`: lift its discarded offset into a shared hit test, alongside the sentence-or-block expansion.
- `reikai/presentation/reader/NovelTextViewport.kt`, `createChunkView` and the `selectableTaps` detector: the native long-press registration.
- `reikai/presentation/reader/NovelWebViewport.kt`, the `webView` block, and `reikai/presentation/reader/web/NovelWebBridge.kt`: the WebView bridge method.
- `app/src/main/assets/novel-web/reader.js`, `installGestures`: the long-press arm.
- `reikai/presentation/reader/ReaderDialog.kt` and `ReaderBottomButtonsScreen.kt`: the sheet pattern to copy.
- `eu/kanade/presentation/more/settings/screen/novel/NovelRegexRulesScreen.kt` and `components/NovelToggleList.kt`, `NovelRegexRuleDialogs.kt`: the list and editor to extend with built-ins and the preview.
- `eu/kanade/presentation/more/settings/screen/SettingsNovelReaderScreen.kt`, `getChapterTextGroup`: where the page is entered from.
- Tests: `app/src/test/java/reikai/novel/content/NovelRegexReplacementsTest.kt` and `NovelContentPipelineTest.kt` for the kernel; `app/src/androidTest/java/reikai/presentation/reader/TextViewportContractTest.kt` for the gesture, which already synthesizes a held press.

## Open questions

1. **blocking.** Match counting. Exact counts need a per-chapter dedupe set held for the life of the chapter; a capped counter is a single int. Recommendation: cap it, because the number exists to spot a runaway rule and a runaway rule passes any threshold quickly.
2. **non-blocking.** Block fallback. A heading or a line with no terminator has no sentence, so the long press would have nothing to hide. Recommendation: fall back to the whole block, as Inkbound does, so injected header junk is reachable.
3. **non-blocking.** Where the settings page lives: the global novel reader settings screen, the in-reader sheet, or both. Recommendation: the global screen, with a link from the in-reader sheet, because the list is long and the sheet is narrow.

## Decisions & tradeoffs

- **Built-ins stay in code with an id denylist.** Storing them as rules would freeze today's pattern in each install, so a fix would never reach anyone who had already read a chapter.
- **No narration-only filter.** Inkbound runs a second `NarrationFilter` that drops ads from the spoken list while leaving them on the page. Reikai does not need it: a rule that hides text hides it from the voice too, and two filter engines can disagree in both directions, which is the class of bug this design removes.
- **Long press hides only while `readerTextSelectable` is off.** When it is on, the TextView Editor owns the touch and no listener fires. Splitting on the existing setting keeps both behaviours reachable and needs no new preference; the settings summary says which is which.
- **Hide scope is global.** Injected junk repeats across every chapter of every book from the same site, so a per-series rule would mean hiding the same ad again in each novel. The match counter is the guard against a rule that is too broad.
- **No EPUB or export path.** Reikai has no novel export, so there is no surface that could leak unfiltered text the way Inkbound's does.
- **Left out:** per-source rule scoping, and a undo stack beyond the single snackbar action.