package me.tarry.app.export.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingController
import me.tarry.app.ui.timeline.DeclinedSuggestionsDataStore
import me.tarry.core.data.db.BackupCounts
import me.tarry.core.data.db.BackupSnapshot
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.SettingsRepository
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** The tracking side of a restore, behind an interface so the restore logic is testable without the service. */
interface RestoreHooks {
    /**
     * Runs [block] with capture stopped and no other tracking operation able to start a session, then reconciles.
     * If [block] throws, what stopping changed is undone and the exception is rethrown (see
     * [TrackingController.withCaptureSuspended]).
     */
    suspend fun <T> captureSuspended(block: suspend () -> T): T

    /** After the data is replaced and tracking reconciled: the segmentation upgrade check. */
    suspend fun afterRestore()
}

/** [RestoreHooks] over the real controller. */
class TrackingRestoreHooks @Inject constructor(
    private val controller: TrackingController,
    private val upgrade: SegmentationUpgrade,
) : RestoreHooks {
    override suspend fun <T> captureSuspended(block: suspend () -> T): T = controller.withCaptureSuspended(block)

    override suspend fun afterRestore() {
        upgrade.runIfNeeded()
    }
}

/** What Settings → Data needs from backup and restore; [BackupManager] is the real one. */
interface DataBackup {
    suspend fun currentCounts(): BackupCounts
    suspend fun backUp(uri: Uri): BackupCounts
    suspend fun preview(uri: Uri): RestorePreview
    suspend fun restore(preview: RestorePreview): BackupCounts
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    abstract fun bindRestoreHooks(impl: TrackingRestoreHooks): RestoreHooks

    @Binds
    abstract fun bindDataBackup(impl: BackupManager): DataBackup
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
) : DataBackup {
    override suspend fun currentCounts(): BackupCounts = store.counts()

    /**
     * Writes a backup to [uri]. Throws [BackupException]: CANNOT_OPEN if the document cannot be opened, WRITE_FAILED if
     * writing fails (the partly written document is then deleted, best effort). Returns what it contains.
     */
    override suspend fun backUp(uri: Uri): BackupCounts = backUp(
        open = {
            context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing")
        },
        discard = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
    )

    /** Reads and validates the backup at [uri]. Throws [BackupException]; changes nothing. */
    override suspend fun preview(uri: Uri): RestorePreview = preview {
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
    }

    suspend fun backUp(open: () -> OutputStream, discard: () -> Unit = {}): BackupCounts = withContext(Dispatchers.IO) {
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
        val out = try {
            open()
        } catch (e: IOException) {
            throw BackupException(BackupError.CANNOT_OPEN, e)
        } catch (e: SecurityException) {
            throw BackupException(BackupError.CANNOT_OPEN, e)
        }
        try {
            out.use { BackupCodec.encode(file, it) }
        } catch (e: Exception) {
            runCatching { discard() }
            if (e is CancellationException) throw e
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
     * Replaces everything on this phone with [preview]. The apply step cannot be cancelled half way: leaving the screen
     * must not leave a new database with old settings. It runs with tracking suspended ([RestoreHooks.captureSuspended]):
     * both DataStores are written, then Room is replaced in one transaction. If anything in that step throws (Room has
     * rolled itself back), the DataStores are put back, the tracking changes are undone and
     * [BackupError.RESTORE_FAILED] is thrown. Once Room has committed nothing is rolled back; tracking then reconciles
     * (a failure there is reported as an outage by the controller, not as a failed restore), and the segmentation
     * upgrade check runs.
     */
    override suspend fun restore(preview: RestorePreview): BackupCounts = withContext(Dispatchers.IO + NonCancellable) {
        val oldSettings = settingsStore.data.first()
        val oldDeclined = declinedStore.data.first()
        try {
            hooks.captureSuspended {
                try {
                    val lastBackup = oldSettings[longPreferencesKey(Settings.LAST_BACKUP_KEY)]
                    settingsStore.updateData { _ ->
                        preview.settings.toMutablePreferences().also {
                            if (lastBackup != null) it[longPreferencesKey(Settings.LAST_BACKUP_KEY)] = lastBackup
                        }
                    }
                    declinedStore.updateData { _ -> preview.declined }
                    store.replaceAll(preview.snapshot, preview.createdAt)
                } catch (e: Exception) {
                    runCatching { settingsStore.updateData { _ -> oldSettings } }
                    runCatching { declinedStore.updateData { _ -> oldDeclined } }
                    throw e
                }
            }
        } catch (e: Exception) {
            throw BackupException(BackupError.RESTORE_FAILED, e)
        }
        try {
            hooks.afterRestore()
        } catch (e: Exception) {
            // The data is restored. The segmentation upgrade check runs again at the next app start.
        }
        preview.counts
    }
}
