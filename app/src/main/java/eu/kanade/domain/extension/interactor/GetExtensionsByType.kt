package eu.kanade.domain.extension.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.repository.ExtensionStoreRepository
import reikai.domain.extension.hasSigningKey
import reikai.domain.extension.signingKeys
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.isInDisabledNovelLanguage

@Inject
class GetExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    private val extensionStoreRepository: ExtensionStoreRepository, // RK
    private val reikaiPreferences: ReikaiSourcePreferences, // RK: the novel language filter
) {

    // RK --> one partition for both apk kinds, over each kind's own lists
    fun subscribe(kind: Extension.Kind = Extension.Kind.MANGA): Flow<Extensions> {
        val enabledContentWarnings = preferences.enabledContentWarnings.get()
        val isManga = kind == Extension.Kind.MANGA

        // Each kind's own language filter: manga's allow list, the novel sources' deny list.
        val isShown: Flow<(String) -> Boolean> = if (isManga) {
            preferences.enabledLanguages.changes().map { enabled -> { lang -> lang in enabled } }
        } else {
            reikaiPreferences.disabledNovelLanguages.changes().map { disabled ->
                { lang -> !lang.isInDisabledNovelLanguage(disabled) }
            }
        }

        return combine(
            isShown,
            if (isManga) extensionManager.loadedExtensionsFlow else extensionManager.loadedNovelExtensionsFlow,
            if (isManga) extensionManager.notLoadedExtensionsFlow else extensionManager.notLoadedNovelExtensionsFlow,
            if (isManga) extensionManager.availableExtensionsFlow else extensionManager.availableNovelExtensionsFlow,
            extensionStoreRepository.getAllAsFlow(),
        ) { isShown, loaded, notLoaded, available, stores ->
            partitionExtensions(
                isShown,
                enabledContentWarnings,
                loaded,
                notLoaded,
                available,
                stores.signingKeys,
            )
        }
    }
    // RK <--
}

// RK --> the interactor's partition, lifted out so both kinds share it; [isShown] is the language filter
internal fun partitionExtensions(
    isShown: (lang: String) -> Boolean,
    enabledContentWarnings: Set<ContentWarning>,
    installed: List<Extension.Loaded>,
    failed: List<Extension.NotLoaded>,
    offered: List<Extension.Available>,
    storeKeys: Set<String>,
): Extensions {
    val byName = compareBy<Extension, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    val (loadedUpdates, loaded) = installed.partition { it.hasUpdate }
    val (notLoadedUpdates, notLoaded) = failed.partition { it.hasUpdate }

    val updates = (loadedUpdates + notLoadedUpdates).sortedWith(byName)

    val installedSignatures = (installed + failed).groupBy({ it.pkgName }, { it.signatures })
    val available = offered
        .filter { extension ->
            installedSignatures[extension.pkgName].orEmpty().none {
                // A keyless store's listing has no key to tell apart, so it hides behind any install
                extension.store.signingKey in it || !extension.store.hasSigningKey
            } &&
                extension.contentWarning in enabledContentWarnings
        }
        .flatMap { ext ->
            ext.sources.filter { isShown(it.lang) }
                .map {
                    ext.copy(
                        name = it.name,
                        lang = it.lang,
                        pkgName = "${ext.pkgName}-${it.id}",
                        sources = listOf(it),
                    )
                }
        }
        .sortedWith(
            compareBy<Extension.Available, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.store.signingKey },
        )

    // The listing that raised the update, so another store's listing of the same package never names it
    val updateVersions = updates.mapNotNull { update ->
        update.findUpdate(offered, storeKeys)?.let { update.pkgName to it.versionName }
    }.toMap()

    return Extensions(
        updates = updates,
        loaded = loaded.sortedWith(compareBy<Extension.Loaded> { !it.isObsolete }.then(byName)),
        available = available,
        notLoaded = notLoaded.sortedWith(byName),
        updateVersions = updateVersions,
    )
}
// RK <--
