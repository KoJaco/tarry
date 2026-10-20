package me.tarry.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<Settings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun setRoundingIncrementMin(minutes: Int) {
        require(minutes in Settings.ALLOWED_ROUNDING_INCREMENTS) { "Invalid rounding increment: $minutes" }
        dataStore.edit { it[Keys.ROUNDING_INCREMENT_MIN] = minutes }
    }

    suspend fun setRoundingMode(mode: RoundingMode) {
        dataStore.edit { it[Keys.ROUNDING_MODE] = mode.name }
    }

    suspend fun setMinBillableMinutes(minutes: Int) {
        require(minutes >= 0) { "minBillableMinutes must be >= 0" }
        dataStore.edit { it[Keys.MIN_BILLABLE_MINUTES] = minutes }
    }

    suspend fun setRoundDailyTotalPerJob(enabled: Boolean) {
        dataStore.edit { it[Keys.ROUND_DAILY_TOTAL_PER_JOB] = enabled }
    }

    suspend fun setTravelAttribution(attribution: TravelAttribution) {
        dataStore.edit { it[Keys.TRAVEL_ATTRIBUTION] = attribution.name }
    }

    /** [days] must be 30, 90, 365, or null (keep forever, stored as a removed key). */
    suspend fun setRawPingRetentionDays(days: Int?) {
        require(days == null || days in Settings.ALLOWED_RETENTION_DAYS) { "Invalid retention days: $days" }
        dataStore.edit {
            if (days == null) {
                it.remove(Keys.RAW_PING_RETENTION_DAYS)
                it[Keys.RETENTION_FOREVER] = true
            } else {
                it.remove(Keys.RETENTION_FOREVER)
                it[Keys.RAW_PING_RETENTION_DAYS] = days
            }
        }
    }

    suspend fun setReviewReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.REVIEW_REMINDER_ENABLED] = enabled }
    }

    suspend fun setReviewReminderMinuteOfDay(minuteOfDay: Int) {
        require(minuteOfDay in 0..1439) { "minuteOfDay must be 0..1439: $minuteOfDay" }
        dataStore.edit { it[Keys.REVIEW_REMINDER_MINUTE] = minuteOfDay }
    }

    suspend fun setTheme(theme: ThemeMode) {
        dataStore.edit { it[Keys.THEME] = theme.name }
    }

    private object Keys {
        val ROUNDING_INCREMENT_MIN = intPreferencesKey("rounding_increment_min")
        val ROUNDING_MODE = stringPreferencesKey("rounding_mode")
        val MIN_BILLABLE_MINUTES = intPreferencesKey("min_billable_minutes")
        val ROUND_DAILY_TOTAL_PER_JOB = booleanPreferencesKey("round_daily_total_per_job")
        val TRAVEL_ATTRIBUTION = stringPreferencesKey("travel_attribution")
        val RAW_PING_RETENTION_DAYS = intPreferencesKey("raw_ping_retention_days")
        val RETENTION_FOREVER = booleanPreferencesKey("raw_ping_retention_forever")
        val REVIEW_REMINDER_ENABLED = booleanPreferencesKey("review_reminder_enabled")
        val REVIEW_REMINDER_MINUTE = intPreferencesKey("review_reminder_minute_of_day")
        val THEME = stringPreferencesKey("theme")
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        val retention = when {
            this[Keys.RETENTION_FOREVER] == true -> null
            else -> this[Keys.RAW_PING_RETENTION_DAYS]
                ?.takeIf { it in Settings.ALLOWED_RETENTION_DAYS } ?: d.rawPingRetentionDays
        }
        return Settings(
            roundingIncrementMin = this[Keys.ROUNDING_INCREMENT_MIN]
                ?.takeIf { it in Settings.ALLOWED_ROUNDING_INCREMENTS } ?: d.roundingIncrementMin,
            roundingMode = enumOrDefault(this[Keys.ROUNDING_MODE], d.roundingMode),
            minBillableMinutes = this[Keys.MIN_BILLABLE_MINUTES]?.takeIf { it >= 0 } ?: d.minBillableMinutes,
            roundDailyTotalPerJob = this[Keys.ROUND_DAILY_TOTAL_PER_JOB] ?: d.roundDailyTotalPerJob,
            travelAttribution = enumOrDefault(this[Keys.TRAVEL_ATTRIBUTION], d.travelAttribution),
            rawPingRetentionDays = retention,
            reviewReminderEnabled = this[Keys.REVIEW_REMINDER_ENABLED] ?: d.reviewReminderEnabled,
            reviewReminderMinuteOfDay = this[Keys.REVIEW_REMINDER_MINUTE]
                ?.takeIf { it in 0..1439 } ?: d.reviewReminderMinuteOfDay,
            theme = enumOrDefault(this[Keys.THEME], d.theme),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
