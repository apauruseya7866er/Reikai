# Audiobook player and lyrics

## Goal

Read-aloud becomes a real audiobook: the notification can seek to an actual sentence, speech keeps going after you leave the reader and turns the page by itself, and a full-screen player shows the chapter as a sentence transcript you can tap to move around in.

## Why

Reikai's read-aloud works, but its state is paragraph-granular. `NovelTtsSession.State` carries a paragraph index and a paragraph count, and the notification's seek bar is a synthetic scale of one second per paragraph, which means a scrub lands somewhere in the chapter rather than on the thing you meant. Playback also stops when you leave the reader: `NovelReaderViewModel.onCleared` shuts the controller down, releasing the engine and resetting the session.

Those two facts together rule out a transcript. A lyric list needs a sentence index to highlight and to seek to, and it needs to keep working when the reader page is gone, because the whole point of an audiobook is that you close the book and keep listening. Inkbound ships both, in a Flutter app whose narration survives the reader widget being disposed.

The chapter text this needs is already reachable without a viewport. `NovelContentPipeline.process` is pure, and `NovelDownloadedTexts.readEach` is the existing viewport-free template for reading stored chapters, used by search and word count.

## Approach

Plain English first: read-aloud needs to know which sentence it is on, not which paragraph, and it needs to own itself rather than borrow the reader's lifecycle. Both are mechanical once the sentence index exists.

**Sentence granularity in the session.** `sentenceIndex` joins `paragraph` in `NovelTtsSession.State`, and the media session's seek is mapped from a real utterance timeline rather than the synthetic paragraph scale. `ReadAloudController` already holds the sentence list and the current index for stepping, so this promotes state it has rather than adding a new concept. The service's prev and next actions stay prev and next sentence, which is what they already do in the controller.

**Speech that outlives the reader.** Ownership moves out of the ViewModel into an app-scoped holder, mirroring how `NovelTtsSession` is already app-scoped. A chapter source reads through the download store and runs the same pipeline the reader runs, so the narration text and the page text cannot diverge. The reader contributes a surface while it is attached and drops it when detached, which the controller already supports today.

**Resume position.** `{chapterId, sentenceIndex, fingerprint}` stored per book, where the fingerprint is a short hash of the sentence text. When the rule set changes and a filter drops sentences, a stored index can be stale, so the fingerprint re-anchors it to the nearest match and the clamped index is the fallback. Without this, hiding one sentence silently moves every saved position.

**The player sheet.** A `ReaderDialog` case rendered as an `AdaptiveSheet`, matching how the reader's other overlays are done. Cover art, the chapter title, a per-sentence waveform bar whose bar heights are `0.22 + chars / 120 * 0.78` (deterministic, so it does not reshuffle on rebuild), a counter with a remaining-minutes estimate, and a transport row. Reuse the existing `TtsSleepTimer` rather than a second sleep cycle.

**The lyrics view.** The same sheet with the middle row swapped: the chapter's spoken sentences, the active one centred and full-opacity, the rest dimmed by distance, auto-revealed as narration advances, tap to seek. Inkbound needs a five-attempt estimate-then-correct scroll loop because its lazy list cannot measure rows it has not built; a Compose `LazyColumn` that measures on the first layout and then scrolls needs none of that.

## Key files

- `reikai/presentation/reader/ReadAloudController.kt`, `ReadAloudState`, `step`, `endChapter` and `seekToParagraph`: the sentence index and the chapter handoff.
- `reikai/data/novel/tts/NovelTtsSession.kt`, `State` and `reset`: gains `sentenceIndex`.
- `reikai/data/novel/tts/NovelTtsService.kt`, `PARAGRAPH_MS` and `sessionCallback`: the synthetic seek scale is replaced, and `onSeekTo` maps a real position.
- `reikai/domain/novel/tts/TtsUtteranceSplitter.kt`, `TtsPiece`: already carries `start` and `end`, which is what a real seek maps onto.
- `reikai/presentation/reader/NovelTtsSessionTransport.kt`, `owns`: the ownership guard that becomes the app-scoped holder's seam.
- `reikai/novel/download/NovelDownloadedTexts.kt`, `readEach`: the viewport-free read template the chapter source copies.
- `reikai/novel/source/NovelChapterTextLoader.kt`, `load`: the pipeline entry a detached chapter source calls.
- `reikai/presentation/reader/ReaderDialog.kt` and `ReaderBottomButtonsScreen.kt`: the sheet pattern for the player.
- `reikai/presentation/reader/ReaderSleepTimerDialog.kt`: how a `ReaderDialog` case carries a capability payload rather than a page.
- `reikai/presentation/reader/settings/NovelReaderSettingsPages.kt`, `NovelReadAloudPage`: where a transcript or resume preference would be added.
- `reikai/presentation/reader/text/ReadAloudBoxDecoration.kt`, `measureLine`: the per-line geometry the highlight already proves, which the transcript's follow-scroll reuses.
- Tests: `app/src/test/java/reikai/presentation/reader/ReadAloudControllerTest.kt` for the controller, `app/src/test/java/reikai/data/novel/tts/NovelTtsSessionTest.kt` for the session state, and `TextViewportContractTest.kt` for anything that crosses into a renderer.

## Open questions

1. **blocking.** Chapter advance while detached. Inkbound asks the reader to turn the page, and when the reader is gone it loads the next chapter itself. Recommendation: the holder loads the next chapter itself and keeps the reader's scroll position out of it entirely, so the reader and the narration are two independent consumers of the same chapter list rather than one driving the other.
2. **blocking.** What the player does when the next chapter is not downloaded. The pipeline can fetch from the source, but a detached holder fetching on the user's mobile data is a decision, not an implementation detail. Recommendation: auto-advance only into chapters already downloaded, and say so at the end of the chapter.
3. **non-blocking.** Whether the waveform bar ships at all. It is a chapter map, not decoration, but it is the one element in Inkbound's player with no counterpart in Reikai today. Recommendation: ship it, since tap-to-seek on it is the cheapest way to move around a long chapter.

## Decisions & tradeoffs

- **Promotion, not a second index.** `ReadAloudController` already tracks `sentenceIndex` for prev and next stepping, so the session gains a field rather than a new model.
- **Resume is fingerprinted, not trusted.** Storing a bare sentence index would break the moment a filter change removed a sentence ahead of it.
- **The chapter source runs the pipeline, never raw markup.** `NovelChapterTextLoader.load` documents that its output is pipeline output and that a renderer must not process it again; the detached source holds to that so narration and page text stay identical.
- **Reuse the existing sleep timer.** The reader already has one with its own settings page and tests.
- **Left out:** a shuffle action, per-sentence gap presets, and the pause-tier tuning Inkbound uses to make narration less mechanical. The last one is a real quality gap, but it changes how speech sounds rather than what the player does, so it belongs to its own decision.