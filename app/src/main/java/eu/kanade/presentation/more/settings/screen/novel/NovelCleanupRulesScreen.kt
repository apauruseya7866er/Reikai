package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.RadioButtonUnchecked
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import reikai.novel.content.CleanupRule
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/**
 * The shipped cleanup rules: one master switch, then each rule the reader can turn off, copy into
 * their own find-and-replace rules, or both. Novel-only by mechanism, since a manga chapter is
 * images the source ships and there is no injected text in it to remove.
 */
class NovelCleanupRulesScreen : Screen() {

    @Composable
    override fun Content() {
        val viewModel = metroViewModel<NovelCleanupRulesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_novel_cleanup_rules),
                    navigateUp = LocalNavigator.currentOrThrow::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            LazyColumn(
                contentPadding = paddingValues + topSmallPaddingValues,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            ) {
                item(key = "enabled") {
                    MasterSwitch(
                        enabled = state.enabled,
                        onToggle = viewModel::setEnabled,
                    )
                }
                item(key = "heading") {
                    Text(
                        text = stringResource(MR.strings.novel_cleanup_shipped_rules),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = MaterialTheme.padding.large),
                    )
                }
                items(state.rules, key = { it.id }) { rule ->
                    val title = stringResource(rule.titleRes())
                    CleanupRuleItem(
                        title = title,
                        pattern = rule.pattern,
                        enabled = state.isOn(rule),
                        onToggle = { viewModel.setRuleEnabled(rule.id, !state.isOn(rule)) },
                        onCopy = { viewModel.showFork(rule) },
                    )
                }
                item(key = "restore") {
                    ElevatedCard(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = state.disabledIds.isNotEmpty()) {
                                    viewModel.restoreAll()
                                }
                                .padding(MaterialTheme.padding.medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Refresh,
                                contentDescription = null,
                            )
                            Text(
                                text = stringResource(MR.strings.novel_cleanup_restore_all),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = MaterialTheme.padding.medium),
                            )
                        }
                    }
                }
            }
        }

        state.forking?.let { rule ->
            // Read once: the callback is not a composable scope, and the name is needed for both.
            val title = stringResource(rule.titleRes())
            ForkDialog(
                title = title,
                onDismissRequest = viewModel::dismissFork,
                onConfirm = { viewModel.fork(rule, title) },
            )
        }
    }
}

/** The rule's name lives in the presentation layer, so the pattern file carries no strings. */
private fun CleanupRule.titleRes(): StringResource = when (id) {
    "builtin_patreon" -> MR.strings.novel_cleanup_rule_patreon
    "builtin_support_me" -> MR.strings.novel_cleanup_rule_support_me
    "builtin_community" -> MR.strings.novel_cleanup_rule_community
    "builtin_advanced_chapters" -> MR.strings.novel_cleanup_rule_advanced_chapters
    "builtin_chapter_footer" -> MR.strings.novel_cleanup_rule_chapter_footer
    "builtin_thanks_reading" -> MR.strings.novel_cleanup_rule_thanks_reading
    "builtin_voting" -> MR.strings.novel_cleanup_rule_voting
    "builtin_novel_promo" -> MR.strings.novel_cleanup_rule_novel_promo
    "builtin_translation_credits" -> MR.strings.novel_cleanup_rule_translation_credits
    "builtin_watermark" -> MR.strings.novel_cleanup_rule_watermark
    "builtin_separator" -> MR.strings.novel_cleanup_rule_separator
    "builtin_bare_link" -> MR.strings.novel_cleanup_rule_bare_link
    "builtin_support_sentence" -> MR.strings.novel_cleanup_rule_support_sentence
    // A rule added in a later build and not yet named here must not take the screen down with it.
    else -> MR.strings.novel_cleanup_rule_separator
}

/** The tick switches the rule; the rest of the row copies it into the reader's own rules. */
@Composable
private fun CleanupRuleItem(
    title: String,
    pattern: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCopy)
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (enabled) {
                        MaterialSymbols.RoundedFilled.CheckCircle
                    } else {
                        MaterialSymbols.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = stringResource(MR.strings.action_enable),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MaterialTheme.padding.small)
                    .alpha(if (enabled) 1f else DISABLED_ALPHA),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                // The pattern itself, because a name cannot be checked against what a chapter comes out
                // looking like, and it is the thing a reader would have to edit a copy of.
                Text(
                    text = pattern,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.ContentCopy,
                    contentDescription = stringResource(MR.strings.novel_cleanup_fork),
                )
            }
        }
    }
}

@Composable
private fun MasterSwitch(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ElevatedCard(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!enabled) }
                .padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = MaterialTheme.padding.small),
            ) {
                Text(
                    text = stringResource(MR.strings.pref_novel_cleanup_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(MR.strings.pref_novel_cleanup_enabled_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** Editing a shipped rule is a copy, so a fix to the pattern still reaches everyone who left it on. */
@Composable
private fun ForkDialog(title: String, onDismissRequest: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.novel_cleanup_fork))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(MR.strings.novel_cleanup_fork_title, title)) },
        text = { Text(text = stringResource(MR.strings.novel_cleanup_fork_body)) },
    )
}
