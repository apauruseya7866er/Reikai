package reikai.novel.registry

import reikai.novel.source.toLangCode

/**
 * The registry a fresh install starts from. Reikai ships with no repo added, so without this the
 * source list is empty until the reader knows to add one by hand.
 */
const val LNREADER_PLUGIN_REGISTRY =
    "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"

/** The language a fresh picker opens on: most of the registry, and the one most readers want. */
const val DEFAULT_SOURCE_LANGUAGE = "en"

/**
 * The languages [entries] declare, English first then the rest alphabetically, so the picker can offer
 * a filter without inventing a language nobody published in.
 */
fun languagesOf(entries: List<LnRegistryEntry>): List<String> = entries
    .mapTo(sortedSetOf()) { it.lang.toLangCode() }
    .sortedWith(compareByDescending<String> { it == DEFAULT_SOURCE_LANGUAGE }.thenBy { it })

/** The entries of one language, or every entry when [language] is null. */
fun entriesIn(entries: List<LnRegistryEntry>, language: String?): List<LnRegistryEntry> =
    if (language == null) entries else entries.filter { it.lang.toLangCode() == language }

/**
 * What a fresh picker has ticked: the default language's sources and nothing else, rather than an
 * empty list the reader has to build by hand or a registry's worth they did not ask for.
 */
fun defaultSelection(entries: List<LnRegistryEntry>): Set<String> = entries
    .filterTo(HashSet()) { it.lang.toLangCode() == DEFAULT_SOURCE_LANGUAGE }
    .mapTo(HashSet()) { it.id }

/** Sources a picker may offer: a registry entry with a script URL, which every registry entry has. */
fun installableEntries(results: Map<String, LnRepoResult>): List<LnRegistryEntry> = results
    .values
    .filterIsInstance<LnRepoResult.Reached>()
    .flatMap { it.entries }
    .filter { it.url.isNotBlank() }
    .distinctBy { it.id }

/** Why one registry could not be read, when none of them could. Empty when something was reached. */
fun unreadableMessage(results: Map<String, LnRepoResult>): String? {
    if (results.values.any { it is LnRepoResult.Reached }) return null
    return results.values.filterIsInstance<LnRepoResult.Unreachable>().firstOrNull()?.message
}
