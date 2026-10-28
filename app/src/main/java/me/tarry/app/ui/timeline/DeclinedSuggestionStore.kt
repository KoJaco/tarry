package me.tarry.app.ui.timeline

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Qualifier

/** Qualifies the `declined_suggestions` Preferences DataStore. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeclinedSuggestionsDataStore

/** Tunables of [DeclinedSuggestionStore]. */
data class DeclinedSuggestionConfig(
    /** The most declined (visit, job) pairs kept; the oldest are dropped past this. */
    val maxEntries: Int = 500,
)

/** `declined_suggestions` DataStore configuration; a corrupt file is replaced by an empty list. */
internal fun createDeclinedSuggestionsDataStore(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = produceFile,
    )

/**
 * The (visit id, job id) pairs the user declined as suggestions, so they are not offered again (also after a restart).
 * Local control state, not synced and not in Room. Stored oldest first as `visitId|jobId` joined by `;`; anything
 * malformed is skipped.
 */
class DeclinedSuggestionStore @Inject constructor(
    @param:DeclinedSuggestionsDataStore private val dataStore: DataStore<Preferences>,
    private val config: DeclinedSuggestionConfig,
) {
    val declined: Flow<Set<Pair<String, String>>> = dataStore.data.map { decode(it[KEY]).toSet() }

    /** Remembers the pair; the oldest beyond [DeclinedSuggestionConfig.maxEntries] are dropped. */
    suspend fun decline(visitId: String, jobId: String) {
        dataStore.edit { p ->
            val kept = decode(p[KEY]).filterNot { it == visitId to jobId } + (visitId to jobId)
            p[KEY] = encode(kept.takeLast(config.maxEntries))
        }
    }

    /** Forgets the pairs whose visit [isLive] says is gone (deleted or never existed). */
    suspend fun prune(isLive: suspend (visitId: String) -> Boolean) {
        val gone = dataStore.data.first().let { decode(it[KEY]) }.map { it.first }.distinct().filterNot { isLive(it) }.toSet()
        if (gone.isEmpty()) return
        dataStore.edit { p -> p[KEY] = encode(decode(p[KEY]).filterNot { it.first in gone }) }
    }

    private fun encode(entries: List<Pair<String, String>>) = entries.joinToString(";") { "${it.first}|${it.second}" }

    private fun decode(raw: String?): List<Pair<String, String>> = raw.orEmpty().split(';').mapNotNull { entry ->
        val c = entry.split('|')
        if (c.size == 2 && c.all { it.isNotEmpty() }) c[0] to c[1] else null
    }

    private companion object {
        val KEY = stringPreferencesKey("declined")
    }
}
