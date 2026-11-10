package me.tarry.app.ui.settings

import me.tarry.app.export.backup.BackupError
import me.tarry.core.data.db.BackupCounts

/** What Settings → Data is doing. The rows stay disabled and a progress row shows while this is set. */
enum class DataBusy { BACKING_UP, READING, RESTORING, DELETING }

/** The confirm dialog's numbers: the chosen backup against what is on this phone now. */
data class RestorePrompt(val backupCreatedAt: Long, val backup: BackupCounts, val phone: BackupCounts)

data class DataUiState(val busy: DataBusy? = null, val prompt: RestorePrompt? = null)

/** One-shot results, shown as snackbars. */
sealed interface DataEvent {
    data class BackedUp(val counts: BackupCounts) : DataEvent
    data class Restored(val counts: BackupCounts) : DataEvent
    data object Deleted : DataEvent
    data class Failed(val error: BackupError) : DataEvent
}
