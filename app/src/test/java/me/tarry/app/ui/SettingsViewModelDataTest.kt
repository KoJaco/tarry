package me.tarry.app.ui

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.tarry.app.export.backup.BackupError
import me.tarry.app.export.backup.BackupException
import me.tarry.app.export.backup.DataBackup
import me.tarry.app.export.backup.RestorePreview
import me.tarry.app.tracking.FakeClock
import me.tarry.app.ui.settings.DataBusy
import me.tarry.app.ui.settings.DataEvent
import me.tarry.app.ui.settings.SettingsViewModel
import me.tarry.core.data.db.BackupCounts
import me.tarry.core.data.db.BackupSnapshot
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeBackup : DataBackup {
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    val restored = mutableListOf<RestorePreview>()
    var backUps = 0
    val counts = BackupCounts(3, 30)

    private suspend fun maybeFail() {
        gate?.await()
        failure?.let { throw it }
    }

    override suspend fun currentCounts() = BackupCounts(1, 2)
    override suspend fun backUp(uri: Uri): BackupCounts {
        backUps++
        maybeFail()
        return counts
    }

    override suspend fun preview(uri: Uri): RestorePreview {
        maybeFail()
        return RestorePreview(5L, counts, BackupSnapshot(), emptyPreferences(), emptyPreferences())
    }

    override suspend fun restore(preview: RestorePreview): BackupCounts {
        restored += preview
        maybeFail()
        return counts
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsViewModelDataTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: TarryDatabase
    private lateinit var backup: FakeBackup
    private lateinit var vm: SettingsViewModel
    private val uri: Uri = Uri.parse("content://test/doc")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backup = FakeBackup()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("s.preferences_pb") })
        vm = SettingsViewModel(settings, ScheduleRepository(db.scheduleDao()), FakeClock(), backup)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
        db.close()
    }

    @Test
    fun backingUpIsBusyWhileItRunsIgnoresASecondRequestAndReportsTheCounts() = runBlocking<Unit> {
        backup.gate = CompletableDeferred()

        vm.backUp(uri)
        assertThat(vm.data.value.busy).isEqualTo(DataBusy.BACKING_UP)
        vm.backUp(uri)
        vm.openBackup(uri)
        assertThat(backup.backUps).isEqualTo(1)
        assertThat(vm.data.value.busy).isEqualTo(DataBusy.BACKING_UP)

        backup.gate!!.complete(Unit)

        assertThat(vm.data.value.busy).isNull()
        assertThat(vm.events.first()).isEqualTo(DataEvent.BackedUp(backup.counts))
    }

    @Test
    fun openingABackupShowsThePromptWithBothCountsAndConfirmRestoresThatPreviewOnce() = runBlocking<Unit> {
        vm.openBackup(uri)

        val prompt = vm.data.value.prompt!!
        assertThat(vm.data.value.busy).isNull()
        assertThat(prompt.backup).isEqualTo(backup.counts)
        assertThat(prompt.phone).isEqualTo(BackupCounts(1, 2))
        assertThat(prompt.backupCreatedAt).isEqualTo(5L)

        vm.confirmRestore()
        vm.confirmRestore() // nothing pending any more

        assertThat(backup.restored).hasSize(1)
        assertThat(vm.data.value.prompt).isNull()
        assertThat(vm.data.value.busy).isNull()
        assertThat(vm.events.first()).isEqualTo(DataEvent.Restored(backup.counts))
    }

    @Test
    fun cancellingForgetsThePendingBackupSoConfirmDoesNothing() = runBlocking<Unit> {
        vm.openBackup(uri)

        vm.cancelRestore()
        vm.confirmRestore()

        assertThat(vm.data.value.prompt).isNull()
        assertThat(backup.restored).isEmpty()
    }

    @Test
    fun confirmWithoutAnOpenedBackupDoesNothing() = runBlocking<Unit> {
        vm.confirmRestore()

        assertThat(backup.restored).isEmpty()
        assertThat(vm.data.value.busy).isNull()
    }

    @Test
    fun restoringIsBusyAndClearsThePromptWhileItRuns() = runBlocking<Unit> {
        vm.openBackup(uri)
        backup.gate = CompletableDeferred()

        vm.confirmRestore()

        assertThat(vm.data.value.busy).isEqualTo(DataBusy.RESTORING)
        assertThat(vm.data.value.prompt).isNull()
        backup.gate!!.complete(Unit)
        assertThat(vm.data.value.busy).isNull()
    }

    @Test
    fun errorsMapToTheirKindAndTheUnexpectedOnesToTheOperationsDefault() = runBlocking<Unit> {
        backup.failure = BackupException(BackupError.TOO_NEW)
        vm.openBackup(uri)
        assertThat(vm.events.first()).isEqualTo(DataEvent.Failed(BackupError.TOO_NEW))
        assertThat(vm.data.value.prompt).isNull()
        assertThat(vm.data.value.busy).isNull()

        backup.failure = IllegalStateException("boom")
        vm.openBackup(uri)
        assertThat(vm.events.first()).isEqualTo(DataEvent.Failed(BackupError.CANNOT_OPEN))

        vm.backUp(uri)
        assertThat(vm.events.first()).isEqualTo(DataEvent.Failed(BackupError.WRITE_FAILED))

        backup.failure = null
        vm.openBackup(uri)
        backup.failure = IllegalStateException("boom")
        vm.confirmRestore()
        assertThat(vm.events.first()).isEqualTo(DataEvent.Failed(BackupError.RESTORE_FAILED))
        assertThat(vm.data.value.busy).isNull()
    }
}
