package reikai.presentation.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.reader.components.ModeSelectionDialog
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsIconGrid
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Orientation picker for the reader's rotation button, both content types. Takes the entry's own flag
 * and hands one back, so the caller owns where it is stored; Mihon's `OrientationSelectDialog`, which
 * read the manga off a settings model, was deleted for it. The grid highlights [resolvedOrientation],
 * since Default has no tile, and Apply writes only a tile the reader tapped, see [ModeSelection].
 */
@Composable
fun ReaderOrientationDialog(
    currentOrientation: Int,
    resolvedOrientation: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = ReaderOrientation.fromPreference(currentOrientation)
    val resolved = ReaderOrientation.fromPreference(resolvedOrientation)
    val selection = remember { ModeSelection<ReaderOrientation>() }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        ModeSelectionDialog(
            onUseDefault = {
                onChange(ReaderOrientation.DEFAULT.flagValue)
                onDismiss()
            }.takeIf { current != ReaderOrientation.DEFAULT },
            onApply = {
                selection.toApply(current)?.let { onChange(it.flagValue) }
                onDismiss()
            },
        ) {
            SettingsIconGrid(MR.strings.rotation_type) {
                items(readerOrientationChoices) { mode ->
                    IconToggleButton(
                        checked = selection.isChecked(mode, resolved),
                        onCheckedChange = { selection.tap(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        imageVector = mode.icon,
                        title = stringResource(mode.stringRes),
                    )
                }
            }
        }
    }
}
