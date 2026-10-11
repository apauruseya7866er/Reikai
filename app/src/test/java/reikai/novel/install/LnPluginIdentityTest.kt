package reikai.novel.install

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata

/** A plugin's id owns its engine and storage, the site sign-in it captured among it. */
class LnPluginIdentityTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(pluginId = ID)))
        coEvery { it.loader.download(any()) } returns "script"
        coEvery { it.host.loadPlugin(any(), any(), any(), any(), any()) } returns LnPluginHarness.info(ID)
    }

    @Test
    fun `a reload holds the script to the id it was installed under`() = runTest {
        harness.installer.ensureLoaded()

        coVerify { harness.host.loadPlugin(any(), any(), any(), any(), ID) }
    }

    @Test
    fun `an install from another address that takes the id drops the captured sign-in`() = runTest {
        harness.installer.installFromUrl(OTHER_URL, LnInstalledPluginMetadata(pluginId = ID))

        verify { harness.host.forgetWebStorage(ID) }
    }

    @Test
    fun `an update from the same address keeps the captured sign-in`() = runTest {
        harness.installer.installFromUrl(URL, LnInstalledPluginMetadata(pluginId = ID))

        verify(exactly = 0) { harness.host.forgetWebStorage(any()) }
    }

    private companion object {
        const val ID = "plugin"
        const val URL = "https://repo.test/plugin.js"
        const val OTHER_URL = "https://other.test/plugin.js"
    }
}
