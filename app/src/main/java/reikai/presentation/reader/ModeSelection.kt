package reikai.presentation.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A quick mode menu's selection (reading mode, rotation). The menu highlights the mode in use, which for
 * a series on Default is inherited, so it starts with nothing picked: Apply writes only a tile the reader
 * tapped, and only when it differs from the series' own stored mode. Tapping the inherited tile still pins it.
 */
class ModeSelection<T> {

    private var picked: T? by mutableStateOf(null)

    fun tap(mode: T) {
        picked = mode
    }

    /** Whether [mode]'s tile is highlighted, with [inUse] the mode the reader resolves for the series. */
    fun isChecked(mode: T, inUse: T): Boolean = mode == (picked ?: inUse)

    /** What Apply writes to a series whose own mode is [stored], or null for nothing. */
    fun toApply(stored: T): T? = picked?.takeIf { it != stored }
}
