package reikai.domain.extension

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

/**
 * The Update button of a badge a store outage kept: one more fetch, then a lasting error rather than a
 * flow that completes, since the row drops its state when the flow completes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionUpdateInstallTest {

    private val unreachable = InstallStep.Error("unreachable")

    @Test
    fun `an update the stores still do not list ends on the unreachable error`() = runTest {
        val steps = mutableListOf<InstallStep>()
        backgroundScope.launch { installUpdate({ null }, {}, { unreachable }, { flowOf() }).toList(steps) }
        runCurrent()

        steps shouldBe listOf(InstallStep.Pending, unreachable)
    }

    @Test
    fun `the unreachable error stays without the flow completing`() = runTest {
        val steps = mutableListOf<InstallStep>()
        val collecting = backgroundScope.launch {
            installUpdate({ null }, {}, { unreachable }, { flowOf() }).toList(steps)
        }
        runCurrent()

        (steps.lastOrNull() to collecting.isCompleted) shouldBe (unreachable to false)
    }

    @Test
    fun `an update the refetch lists is installed`() = runTest {
        var listed: Extension.Available? = null
        val steps = installUpdate(
            findUpdate = { listed },
            refetch = { listed = LISTING },
            unreachable = { unreachable },
            install = { flowOf(InstallStep.Downloading, InstallStep.Installed) },
        ).toList()

        steps shouldBe listOf(InstallStep.Pending, InstallStep.Downloading, InstallStep.Installed)
    }

    @Test
    fun `a held listing installs without a refetch`() = runTest {
        var refetches = 0
        installUpdate({ LISTING }, { refetches++ }, { unreachable }, { flowOf(InstallStep.Installed) }).toList()

        refetches shouldBe 0
    }

    @Test
    fun `callers that ask while a refetch runs share it`() = runTest {
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val refresh = SharedRefresh {
            runs++
            release.await()
        }
        repeat(3) { launch { refresh() } }
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        runs shouldBe 1
    }

    @Test
    fun `a caller after a finished refetch runs another`() = runTest {
        var runs = 0
        val refresh = SharedRefresh { runs++ }
        refresh()
        refresh()

        runs shouldBe 2
    }

    private companion object {
        val LISTING = Extension.Available(
            name = "Entry",
            pkgName = "pkg.entry",
            versionName = "2",
            versionCode = 2,
            libVersion = 1.6,
            lang = "en",
            contentWarning = ContentWarning.SAFE,
            kind = Extension.Kind.MANGA,
            sources = emptyList(),
            apkUrl = "a.apk",
            iconUrl = "a.png",
            store = ExtensionStore(
                indexUrl = "https://a.example/index.pb",
                name = "a",
                badgeLabel = "A",
                signingKey = "keyA",
                contact = ExtensionStore.Contact(website = "https://a.example", discord = null),
                isLegacy = false,
                extensionListUrl = null,
            ),
        )
    }
}
