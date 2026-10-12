package reikai.presentation.webview

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Closes an on-screen WebView page whose renderer died, and says why. Returned from
 * `onRenderProcessGone`, since leaving it unhandled takes the app down with the page. The in-app
 * browser keeps upstream's own copy of this in `WebViewScreenContent`, so a sync stays a straight merge.
 */
fun Context.closeOnRendererGone(detail: RenderProcessGoneDetail, close: () -> Unit): Boolean {
    logcat(LogPriority.ERROR) {
        "WebView renderer gone: crashed=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()}"
    }
    toast(MR.strings.information_webview_crashed, Toast.LENGTH_LONG)
    close()
    return true
}
