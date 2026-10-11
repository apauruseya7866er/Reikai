package reikai.presentation.details

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.merge.DetailsRemoval
import reikai.domain.merge.RemovalPrompt
import reikai.domain.merge.TestMergeManagers

/**
 * What the details heart removes and how it asks, through the group host both details models read and each
 * type's own merge manager. Every remove asks exactly once: the shared confirm, or for a lone E-Hentai gallery
 * its account confirm in place of it. Under a source chip the heart removes that source, and under All it
 * offers the whole group, so the series does not half-vanish.
 */
class DetailsRemovalConformanceTest {

    private suspend fun host(type: ContentType, selected: Long? = null): EntryMergeGroupHost {
        val group = mapOf(OPENED to GROUP, SIBLING to GROUP, OTHER to GROUP)
        val managers = TestMergeManagers(mapOf(type to group), mergingOn = true)
        val host = EntryMergeGroupHost(managers.of(type), longArrayOf(OPENED), emptyFlow(), { _, _ -> }) { emptyList() }
        host.refresh(OPENED)
        host.selectSource(selected)
        return host
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the All view of a merged entry offers every grouped source`(type: ContentType) = runTest {
        host(type).removal(OPENED).offersGroup shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `every grouped source leaves when the grouped choice is kept`(type: ContentType) = runTest {
        host(type).removal(OPENED).targets(removeGrouped = true) shouldContainExactly listOf(OPENED, SIBLING, OTHER)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip removes the source it shows, not the opened entry`(type: ContentType) = runTest {
        host(type, selected = SIBLING).removal(OPENED).targets(removeGrouped = false) shouldContainExactly
            listOf(SIBLING)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip asks with the shared confirm`(type: ContentType) = runTest {
        val removal = host(type, selected = SIBLING).removal(OPENED)

        removal.prompt { false } shouldBe RemovalPrompt.Confirm(removal)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip offers no grouped sources`(type: ContentType) = runTest {
        host(type, selected = SIBLING).removal(OPENED).offersGroup shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the All view asks the shared confirm even over a source's own`(type: ContentType) = runTest {
        val removal = host(type).removal(OPENED)

        removal.prompt { true } shouldBe RemovalPrompt.Confirm(removal)
    }

    @Test
    fun `declining the grouped choice removes only the opened entry`() {
        val removal = DetailsRemoval(OPENED, listOf(OPENED, SIBLING), selectedId = null)

        removal.targets(removeGrouped = false) shouldContainExactly listOf(OPENED)
    }

    @Test
    fun `an entry standing alone asks with the shared confirm`() = runTest {
        val removal = DetailsRemoval(OPENED, listOf(OPENED), selectedId = null)

        removal.prompt { false } shouldBe RemovalPrompt.Confirm(removal)
    }

    @Test
    fun `an entry standing alone offers no grouped sources`() {
        DetailsRemoval(OPENED, listOf(OPENED), selectedId = null).offersGroup shouldBe false
    }

    @Test
    fun `an entry whose source keeps its own confirm asks only that one`() = runTest {
        DetailsRemoval(OPENED, listOf(OPENED), selectedId = null).prompt { it == OPENED } shouldBe
            RemovalPrompt.SourceConfirm(OPENED)
    }

    @Test
    fun `an entry standing alone removes only itself`() {
        DetailsRemoval(OPENED, listOf(OPENED), selectedId = null).targets(removeGrouped = true) shouldContainExactly
            listOf(OPENED)
    }

    private companion object {
        const val OPENED = 1L
        const val SIBLING = 2L
        const val OTHER = 3L
        const val GROUP = 10L
    }
}
