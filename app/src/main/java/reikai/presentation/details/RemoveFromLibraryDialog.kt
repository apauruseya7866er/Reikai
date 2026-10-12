package reikai.presentation.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import reikai.presentation.components.GroupedSourcesCheckbox
import reikai.presentation.components.rememberGroupedSourcesChoice
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The details heart's remove confirm. On a merged entry's All view it offers [groupedSourceCount] grouped
 * sources; unticked, only the opened entry leaves.
 */
@Composable
fun RemoveFromLibraryDialog(
    entryTitle: String,
    groupedSourceCount: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (removeGrouped: Boolean) -> Unit,
) {
    val grouped = rememberGroupedSourcesChoice(groupedSourceCount)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.remove_from_library)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(MR.strings.remove_manga, entryTitle))
                GroupedSourcesCheckbox(grouped)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm(grouped.removesGrouped)
                },
            ) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
