package mihon.feature.upcoming

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.domain.library.CategorySortOrder
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.upcoming.service.UpcomingPreferences

class UpcomingViewModelTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val store = EmittingPreferenceStore()

    @Test
    fun `the category filter lists categories in the category sort order`() = runTest {
        val preferences = ReikaiLibraryPreferences(store)
        preferences.categorySortOrder.set(CategorySortOrder.A_TO_Z)
        val viewModel = main.track(
            UpcomingViewModel(
                getUpcomingManga = mockk(relaxed = true),
                getCategories = mockk {
                    every { subscribe() } returns
                        flowOf(listOf(Category(3L, "Zeta", 0L, 0L), Category(4L, "Alpha", 1L, 0L)))
                },
                upcomingPreferences = UpcomingPreferences(store),
                reikaiLibraryPreferences = preferences,
            ),
        )

        viewModel.categories.first().map { it.name } shouldBe listOf("Alpha", "Zeta")
    }
}
