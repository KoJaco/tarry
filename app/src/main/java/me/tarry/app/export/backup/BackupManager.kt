package me.tarry.app.export.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.tarry.app.BuildConfig
import me.tarry.app.segmentation.SegmentationUpgrade
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingController
import me.tarry.app.ui.timeline.DeclinedSuggestionsDataStore
import me.tarry.core.data.db.BackupCounts
import me.tarry.core.data.db.BackupSnapshot
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.repository.TrackingRepository
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.SettingsRepository
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** The tracking side of a restore, behind an interface so the restore logic is testable without the service. */
interface RestoreHooks {
    /** Ends an open session with USER_STOP and tears capture down, before the data is replaced. */
    suspend fun stopTracking()

    /** After the data is replaced: reconcile with the restored schedule, then the segmentation upgrade check. */
    suspend fun resumeTracking()
}

/** [RestoreHooks] over the real controller: the existing `stop()` path, then `reconcile()`. */
class TrackingRestoreHooks @Inject constructor(
    private val controller: TrackingController,
    private val trackingRepo: TrackingRepository,
    private val upgrade: SegmentationUpgrade,
) : RestoreHooks {
    override suspend fun stopTracking() {
        if (trackingRepo.openSession() != null) controller.stop()
    }

    override suspend fun resumeTracking() {
        controller.reconcile(ReconcileSource.UI)
        upgrade.runIfNeeded()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    abstract fun bindRestoreHooks(impl: TrackingRestoreHooks): RestoreHooks
}

/** A decoded and validated backup waiting for the user's go-ahead. Nothing has been changed yet. */
class RestorePreview internal constructor(
    /** UTC epoch ms the backup was made. */
    val createdAt: Long,
    val counts: BackupCounts,
    internal val snapshot: BackupSnapshot,
    internal val settings: Preferences,
    internal val declined: Preferences,
)

/**
 * Backs up to and restores from a user-chosen document. Settings → Data is the only caller; nothing runs on its own.
 * What is in the file: every Room table, the `settings` DataStore (except `last_backup_ms`, which describes this
 * phone) and the declined suggestions. The `tracking_status` DataStore is local control state and is not included.
 */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: BackupStore,
    private val settingsStore: DataStore<Preferences>,
    @param:DeclinedSuggestionsDataStore private val declinedStore: DataStore<Preferences>,
    private val settingsRepo: SettingsRepository,
    private val hooks: RestoreHooks,
    private val clock: TrackingClock,
) {
    suspend fun currentCounts(): BackupCounts = store.counts()

    /** Writes a backup to [uri]. Throws [BackupException] (CANNOT_OPEN, WRITE_FAILED). Returns what it contains. */
    suspend fun backUp(uri: Uri): BackupCounts = backUp {
        context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing")
    }

    /** Reads and validates the backup at [uri]. Throws [BackupException]; changes nothing. */
    suspend fun preview(uri: Uri): RestorePreview = preview {
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
    }

    suspend fun backUp(open: () -> OutputStream): BackupCounts = withContext(Dispatchers.IO) {
        val snapshot = store.snapshot()
        val file = BackupFile(
            format = BackupCodec.FORMAT,
            formatVersion = BackupCodec.CURRENT_VERSION,
            createdAt = clock.nowMs(),
            zoneId = clock.zone().id,
            appVersionName = BuildConfig.VERSION_NAME,
            settings = settingsStore.data.first().toEntries(skip = setOf(Settings.LAST_BACKUP_KEY)),
            declinedSuggestions = declinedStore.data.first().toEntries(),
            tables = snapshot.toTables(),
        )
        try {
            open().use { BackupCodec.encode(file, it) }
        } catch (e: IOException) {
            throw BackupException(BackupError.WRITE_FAILED, e)
        }
        settingsRepo.setLastBackupMs(file.createdAt)
        snapshot.counts()
    }

    suspend fun preview(open: () -> InputStream): RestorePreview = withContext(Dispatchers.IO) {
        val file = BackupCodec.decode(open)
        val snapshot = BackupCodec.validate(file)
        RestorePreview(file.createdAt, snapshot.counts(), snapshot, file.settings.toPreferences(), file.declinedSuggestions.toPreferences())
    }

    /**
     * Replaces everything on this phone with [preview]. Tracking is stopped first. Both DataStores are written, then
     * Room is replaced in one transaction; if that throws, the DataStores are put back and [BackupError.RESTORE_FAILED]
     * is thrown (Room has rolled itself back). Afterwards tracking reconciles and the segmentation upgrade check runs.
     */
    suspend fun restore(preview: RestorePreview): BackupCounts = withContext(Dispatchers.IO) {
        hooks.stopTracking()
        val oldSettings = settingsStore.data.first()
        val oldDeclined = declinedStore.data.first()
        try {
            val lastBackup = oldSettings[longPreferencesKey(Settings.LAST_BACKUP_KEY)]
            settingsStore.updateData { _ ->
                preview.settings.toMutablePreferences().also { if (lastBackup != null) it[longPreferencesKey(Settings.LAST_BACKUP_KEY)] = lastBackup }
            }
            declinedStore.updateData { _ -> preview.declined }
            store.replaceAll(preview.snapshot)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching { settingsStore.updateData { _ -> oldSettings } }
                runCatching { declinedStore.updateData { _ -> oldDeclined } }
            }
            if (e is CancellationException) throw e
            throw BackupException(BackupError.RESTORE_FAILED, e)
        }
        try {
            hooks.resumeTracking()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The data is restored. The next alarm, app start or user action reconciles again.
        }
        preview.counts
    }
}
