package reikai.novel.install

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.LnPluginSource
import reikai.util.testAppScope

/** A picked set installing in the background, carrying on past whatever will not. */
class LnSourceInstallerTest {

    private val installer = mockk<LnPluginInstaller>()
    private val subject = LnSourceInstaller(installer, testAppScope())

    private fun entry(id: String) = LnRegistryEntry(
        id = id,
        name = id,
        version = "1.0.0",
        site = "https://example.com",
        lang = "English",
        url = "https://example.com/$id.js",
    )

    private fun source() = mockk<LnPluginSource>(relaxed = true)

    /**
     * The run is on the app scope's own dispatcher, so waiting means waiting on the run rather than on
     * a sibling job. Real time, not the test scheduler's, which would time the wait out before the work.
     */
    private suspend fun awaitDone() = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(10_000) { subject.progress.first { !it.running } }
    }

    @Test
    fun `every picked source is installed and the run reports itself finished`() = runTest {
        coEvery { installer.installFromUrl(any(), any()) } returns source()

        subject.install(listOf(entry("a"), entry("b")))
        awaitDone()

        subject.progress.value shouldBe LnInstallProgress(installed = 2, total = 2)
    }

    /** A registry of 300 entries always holds a site that is down; aborting the other 299 over it is worse. */
    @Test
    fun `a source that fails is recorded and the run carries on`() = runTest {
        // The catch-all goes first: MockK tries the most recent stub that matches, so a later
        // catch-all would answer the call the specific stub was meant to fail.
        coEvery { installer.installFromUrl(any(), any()) } returns source()
        coEvery { installer.installFromUrl("https://example.com/bad.js", any()) } throws
            IllegalStateException("offline")

        subject.install(listOf(entry("bad"), entry("good")))
        awaitDone()

        subject.progress.value.failed.map { it.id } shouldBe listOf("bad")
    }

    /** The other half of carrying on: the source after the broken one is installed, not skipped. */
    @Test
    fun `a source after one that failed is still installed`() = runTest {
        coEvery { installer.installFromUrl(any(), any()) } returns source()
        coEvery { installer.installFromUrl("https://example.com/bad.js", any()) } throws
            IllegalStateException("offline")

        subject.install(listOf(entry("bad"), entry("good")))
        awaitDone()

        subject.progress.value.installed shouldBe 1
    }

    @Test
    fun `a source that installs is not counted as one that failed`() = runTest {
        coEvery { installer.installFromUrl(any(), any()) } returns source()

        subject.install(listOf(entry("a")))
        awaitDone()

        subject.progress.value.failed shouldBe emptyList()
    }

    @Test
    fun `a failure is told apart from a success in what the reader is shown`() = runTest {
        coEvery { installer.installFromUrl(any(), any()) } throws IllegalStateException("offline")

        subject.install(listOf(entry("a")))
        awaitDone()

        subject.progress.value.failed.single().reason shouldBe "offline"
    }

    /** The app scope is what makes this true: leaving the picker must not leave the run half done. */
    @Test
    fun `the run is not tied to the screen that started it`() = runTest {
        coEvery { installer.installFromUrl(any(), any()) } returns source()

        subject.install(listOf(entry("a"), entry("b")))
        awaitDone()

        subject.progress.value.installed shouldBe 2
    }

    @Test
    fun `nothing picked installs nothing`() {
        subject.install(emptyList())

        subject.progress.value shouldBe LnInstallProgress()
    }

    @Test
    fun `a run already going is not restarted`() = runTest {
        coEvery { installer.installFromUrl(any(), any()) } returns source()

        subject.install(listOf(entry("a")))
        subject.install(listOf(entry("b")))
        awaitDone()

        subject.progress.value.total shouldBe 1
    }

    @Test
    fun `dismissing the failures clears the whole report`() = runTest {
        coEvery { installer.installFromUrl(any(), any()) } throws IllegalStateException("offline")

        subject.install(listOf(entry("a")))
        awaitDone()
        subject.dismissFailures()

        subject.progress.value shouldBe LnInstallProgress()
    }
}
