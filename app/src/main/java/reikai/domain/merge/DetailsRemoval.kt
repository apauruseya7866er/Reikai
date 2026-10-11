package reikai.domain.merge

/**
 * Which entries the details page's library heart takes out of the library. A source chip removes the
 * source it shows, not the entry the page was opened on. The All view of a merged entry offers every
 * grouped source, as the library's Remove dialog does; declined, only the opened entry leaves. [groupIds]
 * are the library members the page resolved, the opened entry among them.
 */
data class DetailsRemoval(val openedId: Long, val groupIds: List<Long>, val selectedId: Long?) {

    /** Whether the confirm offers [groupIds] as well as the opened entry. */
    val offersGroup: Boolean get() = selectedId == null && groupIds.size > 1

    fun targets(removeGrouped: Boolean): List<Long> =
        if (offersGroup && !removeGrouped) listOf(openedId) else viewedMembers(groupIds, selectedId, openedId)

    /**
     * The one prompt the heart's remove shows: nothing leaves unconfirmed, and nothing asks twice. A lone
     * target whose source keeps a confirm of its own ([hasOwnConfirm], an E-Hentai gallery backed up to the
     * account) shows that one instead; a declined group choice removes without it.
     */
    suspend fun prompt(hasOwnConfirm: suspend (Long) -> Boolean): RemovalPrompt {
        val lone = targets(removeGrouped = false).single()
        val ownConfirm = !offersGroup && hasOwnConfirm(lone)
        return if (ownConfirm) RemovalPrompt.SourceConfirm(lone) else RemovalPrompt.Confirm(this)
    }
}

sealed interface RemovalPrompt {
    /** The shared confirm, offering the grouped sources when [DetailsRemoval.offersGroup]. */
    data class Confirm(val removal: DetailsRemoval) : RemovalPrompt

    /** The source's own confirm for [entryId], in place of the shared one. */
    data class SourceConfirm(val entryId: Long) : RemovalPrompt
}

/**
 * The entries a details page shows: the source chip's alone, else every grouped source, else the
 * opened entry, which also covers a group not resolved yet ([groupIds] empty).
 */
fun viewedMembers(groupIds: List<Long>, selectedId: Long?, openedId: Long): List<Long> = when {
    selectedId != null -> listOf(selectedId)
    groupIds.size > 1 -> groupIds
    else -> listOf(openedId)
}
