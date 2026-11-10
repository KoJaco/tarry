package me.tarry.app.export.backup

import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.segmentation.SegmentationParams
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.core.data.db.BackupSnapshot
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.settings.RoundingMode
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.core.data.settings.ThemeMode
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal class RecordingHooks : RestoreHooks {
    val calls = mutableListOf<String>()
    override suspend fun <T> captureSuspended(block: suspend () -> T): T {
        calls += "suspend"
        return try {
            block().also { calls += "resume" }
        } catch (e: Throwable) {
            calls += "undo"
            throw e
        }
    }
    override suspend fun <T> captureSuspendedKeepingOverrides(block: suspend (activeBefore: List<ManualOverride>) -> T): T =
        captureSuspended { block(emptyList()) }
    var afterGate: (suspend () -> Unit)? = null
    override suspend fun afterRestore() {
        calls += "after"
        afterGate?.invoke()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: TarryDatabase
    private lateinit var store: BackupStore
    private lateinit var settingsStore: DataStore<Preferences>
    private lateinit var declinedStore: DataStore<Preferences>
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var hooks: RecordingHooks
    private lateinit var manager: BackupManager
    private val declinedKey = stringPreferencesKey("declined")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        store = BackupStore(db)
        settingsStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") }
        declinedStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("declined.preferences_pb") }
        settingsRepo = SettingsRepository(settingsStore)
        hooks = RecordingHooks()
        manager = BackupManager(context, store, settingsStore, declinedStore, settingsRepo, hooks, FakeClock(now = 1_700_000_000_000L), SegmentationParams())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun backupBytes(): ByteArray =
        ByteArrayOutputStream().also { out -> manager.backUp(open = { out }) }.toByteArray()

    private suspend fun previewOf(bytes: ByteArray) = manager.preview { ByteArrayInputStream(bytes) }

    @Test
    fun restoreReplacesEverythingOnThePhoneWithTheBackupsRows() = runBlocking<Unit> {
        // Phone A: data, settings, declined suggestions.
        store.replaceAll(everythingSnapshot("a"))
        settingsRepo.setTheme(ThemeMode.DARK)
        settingsRepo.setRoundingMode(RoundingMode.UP)
        declinedStore.edit { it[declinedKey] = "v1|j1" }
        val bytes = backupBytes()
        val backedUp = store.snapshot()

        // Now the phone holds different data and settings.
        store.replaceAll(
            BackupSnapshot(
                sessions = listOf(TrackingSession(77, 1L, 2L, "UTC", SessionTrigger.MANUAL, SessionEndReason.USER_STOP)),
                jobs = listOf(Job("other", "Other", 1, updatedAt = 1L)),
            ),
        )
        settingsRepo.setTheme(ThemeMode.LIGHT)
        settingsRepo.setStayGeofenceRadiusM(50)
        declinedStore.edit { it[declinedKey] = "zzz|yyy" }

        val counts = manager.restore(previewOf(bytes))

        // Overrides still active when the backup was made end at its createdAt (the fake clock).
        val endedAt = 1_700_000_000_000L
        assertThat(store.snapshot()).isEqualTo(
            backedUp.copy(manualOverrides = backedUp.manualOverrides.map { if (it.end == null) it.copy(end = endedAt, active = false) else it }),
        )
        assertThat(counts.visits).isEqualTo(1)
        assertThat(counts.locations).isEqualTo(3)
        val s = settingsRepo.settings.first()
        assertThat(s.theme).isEqualTo(ThemeMode.DARK)
        assertThat(s.roundingMode).isEqualTo(RoundingMode.UP)
        assertThat(s.stayGeofenceRadiusM).isEqualTo(100) // the radius set after the backup is gone
        assertThat(declinedStore.data.first()[declinedKey]).isEqualTo("v1|j1")
        assertThat(hooks.calls).containsExactly("suspend", "resume", "after").inOrder()
    }

    @Test
    fun backingUpRecordsWhenAndKeepsThatTimestampOutOfTheFileAndAcrossARestore() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot())
        val first = backupBytes()
        assertThat(settingsRepo.settings.first().lastBackupMs).isEqualTo(1_700_000_000_000L)

        val second = backupBytes() // would carry last_backup_ms if it were exported
        assertThat(decodeSettingsKeys(second)).doesNotContain("last_backup_ms")

        manager.restore(previewOf(first))
        assertThat(settingsRepo.settings.first().lastBackupMs).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun theOnboardingStampStaysOutOfTheFileAndSurvivesARestoreAndADeleteAll() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot())
        settingsRepo.setOnboardingCompletedAtMs(123L)
        val bytes = backupBytes()
        assertThat(decodeSettingsKeys(bytes)).doesNotContain(Settings.ONBOARDING_COMPLETED_AT_KEY)

        manager.restore(previewOf(bytes))
        assertThat(settingsRepo.onboardingCompletedAtMs()).isEqualTo(123L)

        manager.deleteAll()
        assertThat(settingsRepo.onboardingCompletedAtMs()).isEqualTo(123L)
    }

    private fun decodeSettingsKeys(bytes: ByteArray) =
        decodeAll { ByteArrayInputStream(bytes) }.settings.map { it.key }

    @Test
    fun aFailureMidInsertLeavesTheDatabaseAndBothDataStoresUnchanged() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot("keep"))
        settingsRepo.setTheme(ThemeMode.DARK)
        declinedStore.edit { it[declinedKey] = "keep|keep" }
        val dbBefore = store.snapshot()
        val settingsBefore = settingsStore.data.first()
        val declinedBefore = declinedStore.data.first()

        // A preview that validation would have refused (ping without a session), forced through to the apply step.
        val bad = everythingSnapshot("new").copy(
            pings = listOf(LocationPing(5, 1L, "UTC", 0.0, 0.0, 1f, null, null, null, PingSource.FUSED, sessionId = 404)),
        )
        val badBytes = encodeFile(fileOf(bad))
        val badPreview = RestorePreview(
            createdAt = 1L,
            counts = bad.counts(),
            snapshot = bad.copy(pings = emptyList()),
            settings = listOf(PrefEntry("theme", "string", "LIGHT")).toPreferences(),
            declined = listOf(PrefEntry("declined", "string", "new|new")).toPreferences(),
            pingCount = 1,
            open = { ByteArrayInputStream(badBytes) },
        )

        val e = assertThrows(BackupException::class.java) { runBlocking { manager.restore(badPreview) } }

        assertThat(e.error).isEqualTo(BackupError.RESTORE_FAILED)
        assertThat(hooks.calls).containsExactly("suspend", "undo").inOrder()
        assertThat(store.snapshot()).isEqualTo(dbBefore)
        assertThat(settingsStore.data.first()).isEqualTo(settingsBefore)
        assertThat(declinedStore.data.first()).isEqualTo(declinedBefore)
    }

    @Test
    fun aFileChangedBetweenPreviewAndRestoreFailsTheRestoreAndChangesNothing() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot("a"))
        settingsRepo.setTheme(ThemeMode.DARK)
        val full = backupBytes()
        // Phone now holds other data and settings.
        store.replaceAll(BackupSnapshot(jobs = listOf(Job("other", "Other", 1, updatedAt = 1L))))
        settingsRepo.setTheme(ThemeMode.LIGHT)
        declinedStore.edit { it[declinedKey] = "keep|keep" }
        val dbBefore = store.snapshot()
        val settingsBefore = settingsStore.data.first()
        val declinedBefore = declinedStore.data.first()

        // Pass 1 sees the full file; by pass 2 it has lost its last ping line.
        val shorter = encodeFile(fileOf(everythingSnapshot("a").let { it.copy(pings = it.pings.dropLast(1)) }))
        var opens = 0
        val preview = manager.preview { ByteArrayInputStream(if (opens++ == 0) full else shorter) }
        assertThat(preview.counts.locations).isEqualTo(3)

        val e = assertThrows(BackupException::class.java) { runBlocking { manager.restore(preview) } }

        assertThat(e.error).isEqualTo(BackupError.RESTORE_FAILED)
        assertThat(hooks.calls).containsExactly("suspend", "undo").inOrder()
        assertThat(store.snapshot()).isEqualTo(dbBefore)
        assertThat(settingsStore.data.first()).isEqualTo(settingsBefore)
        assertThat(declinedStore.data.first()).isEqualTo(declinedBefore)
    }

    @Test
    fun aFileSwappedForOneWithTheSameCountButOutOfOrderPingsIsRejectedAtRestoreAndChangesNothing() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot("a"))
        val full = backupBytes()
        store.replaceAll(BackupSnapshot(jobs = listOf(Job("other", "Other", 1, updatedAt = 1L))))
        val dbBefore = store.snapshot()
        val settingsBefore = settingsStore.data.first()

        val swapped = encodeFile(fileOf(everythingSnapshot("a").let { it.copy(pings = it.pings.reversed()) }))
        var opens = 0
        val preview = manager.preview { ByteArrayInputStream(if (opens++ == 0) full else swapped) }

        val e = assertThrows(BackupException::class.java) { runBlocking { manager.restore(preview) } }

        assertThat(e.error).isEqualTo(BackupError.RESTORE_FAILED)
        assertThat((e.cause as BackupException).error).isEqualTo(BackupError.DAMAGED)
        assertThat(store.snapshot()).isEqualTo(dbBefore)
        assertThat(settingsStore.data.first()).isEqualTo(settingsBefore)
    }

    @Test
    fun aPingWithAnUnknownSessionOrADuplicateIdIsDamagedAtPreview()= runBlocking<Unit> {
        val s = everythingSnapshot()
        val unknownSession = encodeFile(fileOf(s.copy(pings = s.pings + s.pings.first().copy(id = 99, sessionId = 404))))
        val duplicateId = encodeFile(fileOf(s.copy(pings = s.pings + s.pings.first().copy(timestamp = 7L))))

        for (bytes in listOf(unknownSession, duplicateId)) {
            val e = assertThrows(BackupException::class.java) { runBlocking { previewOf(bytes) } }
            assertThat(e.error).isEqualTo(BackupError.DAMAGED)
        }
    }

    @Test
    fun invalidFilesAreRejectedBeforeAnythingChanges() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot("keep"))
        val before = store.snapshot()
        val settingsBefore = settingsStore.data.first()

        val newer = encodeFile(fileOf(everythingSnapshot(), version = BackupCodec.CURRENT_VERSION + 1))
        val notGzip = """{"format":"tarry-backup","formatVersion":1}""".toByteArray()
        val orphanPing = everythingSnapshot().let { s -> fileOf(s.copy(pings = s.pings.map { it.copy(sessionId = 404) })) }

        for ((bytes, error) in listOf(
            newer to BackupError.TOO_NEW,
            notGzip to BackupError.NOT_A_BACKUP,
            encodeFile(orphanPing) to BackupError.DAMAGED,
        )) {
            val e = assertThrows(BackupException::class.java) { runBlocking { previewOf(bytes) } }
            assertThat(e.error).isEqualTo(error)
        }

        assertThat(store.snapshot()).isEqualTo(before)
        assertThat(settingsStore.data.first()).isEqualTo(settingsBefore)
        assertThat(hooks.calls).isEmpty()
    }

    @Test
    fun aBackupTakenMidSessionRestoresWithThatSessionClosedAtItsLastPingAndNoGap() = runBlocking<Unit> {
        // Encoded straight from a snapshot, the way a backup taken on a live phone holds the still-open row.
        val open = everythingSnapshot(openSession = true).copy(gaps = emptyList())
        val bytes = ByteArrayOutputStream().also { BackupCodec.encode(fileOf(open), it) }.toByteArray()

        manager.restore(previewOf(bytes))

        assertThat(db.trackingSessionDao().getOpenSession()).isNull()
        val closed = db.trackingSessionDao().getById(2)!!
        assertThat(closed.end).isEqualTo(5_500L)
        assertThat(closed.endReason).isEqualTo(SessionEndReason.KILLED)
        assertThat(store.snapshot().gaps).isEmpty()
    }

    @Test
    fun leavingTheScreenAfterRoomCommittedDoesNotPutTheOldSettingsBack() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot("a"))
        settingsRepo.setTheme(ThemeMode.DARK)
        val bytes = backupBytes()
        store.replaceAll(BackupSnapshot(jobs = listOf(Job("other", "Other", 1, updatedAt = 1L))))
        settingsRepo.setTheme(ThemeMode.LIGHT)
        val preview = previewOf(bytes)
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        hooks.afterGate = {
            reached.complete(Unit)
            gate.await()
        }

        val job = launch(Dispatchers.Default) { manager.restore(preview) }
        reached.await() // Room has committed and the settings are written
        job.cancel() // the user left the screen
        gate.complete(Unit)
        job.join()

        assertThat(store.snapshot().jobs.map { it.id }).containsExactly("j1", "j2")
        assertThat(settingsRepo.settings.first().theme).isEqualTo(ThemeMode.DARK)
    }

    @Test
    fun aDocumentThatCannotBeOpenedIsCannotOpenAndAFailedWriteDiscardsTheDocument() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot())

        val e1 = assertThrows(BackupException::class.java) {
            runBlocking { manager.backUp(open = { throw java.io.IOException("gone") }) }
        }
        assertThat(e1.error).isEqualTo(BackupError.CANNOT_OPEN)

        var discarded = false
        val failing = object : java.io.OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("disk full")
        }
        val e2 = assertThrows(BackupException::class.java) {
            runBlocking { manager.backUp(open = { failing }, discard = { discarded = true }) }
        }
        assertThat(e2.error).isEqualTo(BackupError.WRITE_FAILED)
        assertThat(discarded).isTrue()
        assertThat(settingsRepo.settings.first().lastBackupMs).isNull()
    }

    @Test
    fun deleteAllStopsTrackingThenDeletesThenResumesAndKeepsTheSettings() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot("a"))
        settingsRepo.setTheme(ThemeMode.DARK)
        settingsRepo.setRoundingMode(RoundingMode.UP)
        settingsRepo.setLastBackupMs(5L)
        declinedStore.edit { it[declinedKey] = "v1|j1" }
        val events = mutableListOf<String>()
        val ordered = object : RestoreHooks {
            override suspend fun <T> captureSuspended(block: suspend () -> T): T {
                events += "stop (rows: ${store.counts().locations})"
                return block().also { events += "resume (rows: ${store.counts().locations})" }
            }

            override suspend fun <T> captureSuspendedKeepingOverrides(block: suspend (activeBefore: List<ManualOverride>) -> T): T =
                captureSuspended { block(emptyList()) }

            override suspend fun afterRestore() {
                events += "after"
            }
        }
        val deleting = BackupManager(
            ApplicationProvider.getApplicationContext<Context>(), store, settingsStore, declinedStore, settingsRepo, ordered,
            FakeClock(now = 1_700_000_000_000L),
            SegmentationParams(),
        )

        deleting.deleteAll()

        assertThat(events).containsExactly("stop (rows: 3)", "resume (rows: 0)", "after").inOrder()
        val snapshot = store.snapshot()
        assertThat(snapshot.copy(scheduleTemplates = emptyList(), scheduleOverrides = emptyList())).isEqualTo(BackupSnapshot())
        val s = settingsRepo.settings.first()
        assertThat(s.theme).isEqualTo(ThemeMode.DARK)
        assertThat(s.roundingMode).isEqualTo(RoundingMode.UP)
        assertThat(s.lastBackupMs).isEqualTo(5L)
        assertThat(declinedStore.data.first()[declinedKey]).isNull()
    }

    @Test
    fun deleteAllFailureIsReportedAndLeavesTheDataAlone() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot("a"))
        val failing = object : RestoreHooks {
            override suspend fun <T> captureSuspended(block: suspend () -> T): T = throw java.io.IOException("cannot stop")
            override suspend fun <T> captureSuspendedKeepingOverrides(block: suspend (activeBefore: List<ManualOverride>) -> T): T =
                captureSuspended { block(emptyList()) }
            override suspend fun afterRestore() = Unit
        }
        val deleting = BackupManager(
            ApplicationProvider.getApplicationContext<Context>(), store, settingsStore, declinedStore, settingsRepo, failing,
            FakeClock(now = 1_700_000_000_000L),
            SegmentationParams(),
        )

        val e = assertThrows(BackupException::class.java) { runBlocking { deleting.deleteAll() } }

        assertThat(e.error).isEqualTo(BackupError.DELETE_FAILED)
        assertThat(store.counts().locations).isEqualTo(3)
    }

    @Test
    fun restoringABackupMadeAfterPruningSetsTheWatermarkAtItsEarliestPing() = runBlocking<Unit> {
        val late = 10_000_000L // sessions start at 1_000 and 5_000, well over the overlap before this
        val pruned = everythingSnapshot().copy(
            pings = listOf(
                LocationPing(10, late + 5, "UTC", 0.0, 0.0, 5f, null, null, null, PingSource.FUSED, 1),
                LocationPing(11, late, "UTC", 0.0, 0.0, 5f, null, null, null, PingSource.FUSED, 1),
            ),
        )
        store.replaceAll(pruned)
        val bytes = backupBytes()
        settingsRepo.setPrunedBeforeMs(42L)

        manager.restore(previewOf(bytes))

        assertThat(settingsRepo.prunedBeforeMs()).isEqualTo(late)
    }

    @Test
    fun restoringABackupWithAllItsPingsClearsTheWatermark() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot())
        val bytes = backupBytes()
        settingsRepo.setPrunedBeforeMs(42L)

        manager.restore(previewOf(bytes))

        assertThat(settingsRepo.prunedBeforeMs()).isNull()
    }

    @Test
    fun theWatermarkIsNotWrittenToABackupFile() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot())
        settingsRepo.setPrunedBeforeMs(42L)

        assertThat(decodeSettingsKeys(backupBytes())).doesNotContain(Settings.PRUNED_BEFORE_KEY)
    }

    @Test
    fun deleteAllClearsTheWatermark() = runBlocking<Unit> {
        store.replaceAll(everythingSnapshot())
        settingsRepo.setPrunedBeforeMs(42L)

        manager.deleteAll()

        assertThat(settingsRepo.prunedBeforeMs()).isNull()
    }
}
