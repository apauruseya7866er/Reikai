package reikai.presentation.widget

import tachiyomi.domain.updates.model.UpdatesWithRelations

/**
 * Narrows the manga updates a cover widget draws to one row per series, a merged series counting
 * once. Merge groups live in the app module, which binds this, so Mihon's widgets reach them here.
 */
interface MangaUpdatesPerSeries {
    suspend fun onePerSeries(updates: List<UpdatesWithRelations>): List<UpdatesWithRelations>
}
