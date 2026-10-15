package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.BuiltinCleanupRules
import reikai.novel.content.CleanupRule
import reikai.novel.content.NovelRegexReplacement
import reikai.novel.content.NovelRegexRules
import reikai.novel.content.NovelStoredToggleList

@Immutable
data class NovelCleanupRulesState(
    val rules: List<CleanupRule> = emptyList(),
    val enabled: Boolean = true,
    val disabledIds: Set<String> = emptySet(),
    val forking: CleanupRule? = null,
) {
    fun isOn(rule: CleanupRule) = enabled && rule.id !in disabledIds
}

/**
 * The shipped cleanup rules. Turning one off records its id rather than deleting it, so a later fix
 * to a pattern reaches every reader who left it on, and the master switch gates only these: a
 * find-and-replace rule the reader wrote is theirs.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelCleanupRulesViewModel(
    private val preferences: NovelPreferences,
) : ViewModel() {

    private val readerRules = NovelStoredToggleList(preferences.readerRegexReplacements(), NovelRegexRules)

    private val forking = MutableStateFlow<CleanupRule?>(null)

    val state: StateFlow<NovelCleanupRulesState> = combine(
        preferences.readerCleanupEnabled().changes(),
        preferences.readerDisabledCleanupIds().changes(),
        forking,
    ) { enabled, disabledIds, forkingRule ->
        NovelCleanupRulesState(BuiltinCleanupRules.ALL, enabled, disabledIds, forkingRule)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NovelCleanupRulesState())

    fun setEnabled(enabled: Boolean) {
        preferences.readerCleanupEnabled().set(enabled)
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        val disabled = preferences.readerDisabledCleanupIds().get().toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        preferences.readerDisabledCleanupIds().set(disabled)
    }

    fun restoreAll() {
        preferences.readerDisabledCleanupIds().set(emptySet())
    }

    fun showFork(rule: CleanupRule) {
        forking.value = rule
    }

    fun dismissFork() {
        forking.value = null
    }

    /** The copy is the reader's, so the shipped rule steps aside rather than removing the same text twice. */
    fun fork(rule: CleanupRule, title: String) {
        setRuleEnabled(rule.id, enabled = false)
        readerRules.save(
            NovelRegexReplacement(
                title = title,
                pattern = rule.pattern,
                replacement = "",
                isRegex = true,
                caseSensitive = false,
            ),
        )
        dismissFork()
    }
}
