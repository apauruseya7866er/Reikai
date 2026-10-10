package reikai.presentation.browse.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.extension.model.InstallStep
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** One button at the end of an extension or plugin row. */
data class ExtensionAction(val icon: ImageVector, val label: StringResource, val onClick: () -> Unit)

/**
 * A row's actions as plain icon buttons, the secondary one first, so the apk and plugin rows read as
 * one list. A failed install passes [failedInstall] in place of its secondary action.
 */
@Composable
fun ExtensionActionButtons(secondary: ExtensionAction?, primary: ExtensionAction?) {
    listOfNotNull(secondary, primary).forEach { (icon, label, onClick) ->
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = stringResource(label))
        }
    }
}

/** Why an install failed, kept where upstream shows only Retry, since both lists carry the message. */
fun failedInstall(error: InstallStep.Error, onShow: (InstallStep.Error) -> Unit) =
    ExtensionAction(MaterialSymbols.Rounded.Error, MR.strings.ext_install_error_details) { onShow(error) }

fun retryInstall(onRetry: () -> Unit) = ExtensionAction(
    MaterialSymbols.Rounded.Refresh,
    MR.strings.action_retry,
    onRetry,
)
