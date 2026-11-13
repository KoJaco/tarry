package me.tarry.app.export.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import me.tarry.core.data.settings.Settings

/** DataStore preferences as typed key/value entries and back. Keys in [skip] are left out. */
internal fun Preferences.toEntries(skip: Set<String> = emptySet()): List<PrefEntry> =
    asMap().entries.filter { it.key.name !in skip }.mapNotNull { (key, v) ->
        val name = key.name
        when (v) {
            is Int -> PrefEntry(name, "int", v.toString())
            is Long -> PrefEntry(name, "long", v.toString())
            is Float -> PrefEntry(name, "float", v.toString())
            is Double -> PrefEntry(name, "double", v.toString())
            is Boolean -> PrefEntry(name, "boolean", v.toString())
            is String -> PrefEntry(name, "string", v)
            is Set<*> -> PrefEntry(name, "stringSet", values = v.map { it.toString() }.sorted())
            else -> null
        }
    }.sortedBy { it.key }

/**
 * Throws [BackupException] (DAMAGED) for a value that does not parse as its type, so a bad file is rejected before
 * anything is written. An entry of an unknown type is skipped (a later version's key).
 */
internal fun List<PrefEntry>.toPreferences(): Preferences {
    if (map { it.key }.toSet().size != size) throw BackupException(BackupError.DAMAGED)
    val prefs = mutablePreferencesOf()
    try {
        for (e in this) {
            when (e.type) {
                "int" -> prefs[intPreferencesKey(e.key)] = e.value.toInt()
                "long" -> prefs[longPreferencesKey(e.key)] = e.value.toLong()
                "float" -> prefs[floatPreferencesKey(e.key)] = e.value.toFloat()
                "double" -> prefs[doublePreferencesKey(e.key)] = e.value.toDouble()
                "boolean" -> prefs[booleanPreferencesKey(e.key)] = e.value.toBooleanStrict()
                "string" -> prefs[stringPreferencesKey(e.key)] = e.value
                "stringSet" -> prefs[stringSetPreferencesKey(e.key)] = e.values.toSet()
            }
        }
    } catch (e: IllegalArgumentException) {
        throw BackupException(BackupError.DAMAGED, e)
    }
    return prefs
}

/**
 * These preferences with every device-local key ([Settings.DEVICE_LOCAL_KEYS]) taken from [own] instead: any such key in the
 * file is dropped, then the phone's own value is put back, and a key the phone does not have stays absent.
 */
@Suppress("UNCHECKED_CAST")
internal fun Preferences.withDeviceLocalFrom(own: Preferences): Preferences {
    val result = toMutablePreferences()
    result.asMap().keys.filter { it.name in Settings.DEVICE_LOCAL_KEYS }.forEach { result.remove(it) }
    own.asMap().forEach { (key, value) ->
        if (key.name in Settings.DEVICE_LOCAL_KEYS) result[key as Preferences.Key<Any>] = value
    }
    return result
}
