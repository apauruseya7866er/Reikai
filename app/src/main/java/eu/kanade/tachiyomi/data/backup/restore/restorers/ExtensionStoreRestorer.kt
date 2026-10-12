package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository

@Inject
class ExtensionStoreRestorer(
    private val extensionStoreRepository: ExtensionStoreRepository,
) {

    suspend operator fun invoke(
        backupStore: BackupExtensionStore,
    ) {
        // RK --> the key decides which apks count as trusted, so a store already here keeps its own
        val deviceKey = extensionStoreRepository.getAll().firstOrNull { it.indexUrl == backupStore.indexUrl }
            ?.signingKey
        // RK <--
        extensionStoreRepository.upsert(
            ExtensionStore(
                indexUrl = backupStore.indexUrl,
                name = backupStore.name,
                badgeLabel = backupStore.badgeLabel ?: backupStore.name,
                signingKey = deviceKey ?: backupStore.signingKey, // RK
                contact = ExtensionStore.Contact(
                    website = backupStore.contactWebsite,
                    discord = backupStore.contactDiscord,
                ),
                isLegacy = backupStore.isLegacy ?: true,
                extensionListUrl = backupStore.extensionListUrl,
            ),
        )
    }
}
