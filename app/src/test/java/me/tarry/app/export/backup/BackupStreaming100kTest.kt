package me.tarry.app.export.backup

import me.tarry.segmentation.SegmentationParams
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.core.data.db.BackupSnapshot
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Locations are about 99% of the rows (100k at 90 days), so they must never all be in memory on backup or restore.
 * Synthetic 100k-ping round trip through [BackupManager]: back up, preview (pass 1), restore (pass 2) into a database
 * holding other data, then compare every ping row. Retained heap (used after `System.gc()`) is sampled in the middle of
 * each streaming phase and printed next to what holding the pings costs (the old approach held the DTO list and the entity
 * list at once). The heap figures are not asserted (System.gc() is too unreliable to gate CI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupStreaming100kTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var source: TarryDatabase
    private lateinit var target: TarryDatabase
    private lateinit var settingsStore: DataStore<Preferences>
    private lateinit var declinedStore: DataStore<Preferences>
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var manager: BackupManager
    private lateinit var targetManager: BackupManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        target = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        settingsStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") }
        declinedStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("declined.preferences_pb") }
        settingsRepo = SettingsRepository(settingsStore)
        val clock = FakeClock(now = 1_700_000_000_000L)
        manager = BackupManager(context, BackupStore(source), settingsStore, declinedStore, settingsRepo, RecordingHooks(), clock, SegmentationParams())
        targetManager = BackupManager(context, BackupStore(target), settingsStore, declinedStore, settingsRepo, RecordingHooks(), clock, SegmentationParams())
    }

    @After
    fun tearDown() {
        scope.cancel()
        source.close()
        target.close()
    }

    private fun usedHeapMb(): Double {
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
    }

    private fun ping(i: Int) = LocationPing(
        id = i.toLong(),
        timestamp = 1_700_000_000_000L + i * 5_000L,
        zoneId = "Australia/Sydney",
        lat = -33.8688 + (i % 997) * 0.00011,
        lng = 151.2093 + (i % 991) * 0.00013,
        accuracyM = 3.5f + (i % 7),
        speedMps = if (i % 3 == 0) null else (i % 40) * 0.5f,
        bearing = if (i % 5 == 0) null else (i % 360).toFloat(),
        activity = if (i % 4 == 0) null else ActivityType.entries[i % ActivityType.entries.size],
        source = PingSource.entries[i % PingSource.entries.size],
        sessionId = (i % SESSIONS + 1).toLong(),
    )

    private suspend fun allPings(db: TarryDatabase): List<LocationPing> {
        val store = BackupStore(db)
        val out = ArrayList<LocationPing>()
        var after = 0L
        while (true) {
            val page = store.pingsPage(after, Long.MAX_VALUE, 2_000)
            out += page
            if (page.size < 2_000) return out
            after = page.last().id
        }
    }

    /** Calls [sample] every [every]th write. */
    private class SamplingOut(out: OutputStream, private val every: Int, private val sample: () -> Unit) : FilterOutputStream(out) {
        private var calls = 0
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (++calls % every == 0) sample()
            out.write(b, off, len)
        }
    }

    /** Calls [sample] once, when [atBytes] compressed bytes have been read. */
    private class SamplingIn(input: InputStream, private val atBytes: Long, private val sample: () -> Unit) : FilterInputStream(input) {
        private var read = 0L
        private var done = false
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) read += n
            if (!done && read >= atBytes) {
                done = true
                sample()
            }
            return n
        }
    }

    @Test
    fun aHundredThousandPingsRoundTripWithoutHoldingThemInMemory() = runBlocking<Unit> {
        val sessions = (1..SESSIONS).map { TrackingSession(it.toLong(), 1L, 2L, "UTC", SessionTrigger.SCHEDULE, SessionEndReason.SCHEDULE_END) }
        BackupStore(source).replaceAllStreaming(BackupSnapshot(sessions = sessions, jobs = listOf(Job("j", "Site", 1, updatedAt = 1L)))) { insert ->
            (1..PINGS).chunked(500).forEach { ids -> insert(ids.map(::ping)) }
        }
        BackupStore(target).replaceAll(
            BackupSnapshot(
                sessions = listOf(TrackingSession(900, 1L, 2L, "UTC", SessionTrigger.MANUAL, SessionEndReason.USER_STOP)),
                pings = (1..50).map { ping(it).copy(sessionId = 900) },
                jobs = listOf(Job("other", "Other", 2, updatedAt = 2L)),
            ),
        )
        val file = File(folder.root, "backup.json.gz")
        val base = usedHeapMb()
        val t0 = System.nanoTime()

        // Back up: sample during the page loop (every 8th write to the file).
        val backupSamples = mutableListOf<Double>()
        val counts = manager.backUp(open = { SamplingOut(file.outputStream(), 8) { backupSamples += usedHeapMb() - base } })
        val t1 = System.nanoTime()
        assertThat(counts.locations).isEqualTo(PINGS)
        assertThat(backupSamples).isNotEmpty()

        // Preview (pass 1) and restore (pass 2): sample once each, halfway through the file (about the 50,000th ping).
        val half = file.length() / 2
        val passSamples = mutableListOf<Double>()
        val preview = targetManager.preview { SamplingIn(file.inputStream(), half) { passSamples += usedHeapMb() - base } }
        val t2 = System.nanoTime()
        assertThat(preview.counts.locations).isEqualTo(PINGS)
        val restored = targetManager.restore(preview)
        val t3 = System.nanoTime()
        assertThat(restored.locations).isEqualTo(PINGS)
        assertThat(passSamples).hasSize(2)

        // Every ping row, in order, and the counts.
        val expected = allPings(source)
        assertThat(expected).hasSize(PINGS)
        assertThat(allPings(target)).isEqualTo(expected)
        assertThat(BackupStore(target).counts().locations).isEqualTo(PINGS)
        assertThat(BackupStore(target).snapshot().jobs.map { it.id }).containsExactly("j")

        // What holding the pings costs: the old path held the decoded DTOs and the entities together.
        val before = usedHeapMb()
        val decoded = decodeAll { file.inputStream() }
        val entities = BackupCodec.validate(decoded)
        val held = usedHeapMb() - before
        check(decoded.tables.pings.size + entities.pings.size == 2 * PINGS)

        println(
            "STREAM_100K file=${file.length() / 1024} KB; backup ${(t1 - t0) / 1_000_000} ms, preview ${(t2 - t1) / 1_000_000} ms, " +
                "restore ${(t3 - t2) / 1_000_000} ms; heap growth over start (MB): backup max ${"%.1f".format(backupSamples.max())} " +
                "(n=${backupSamples.size}), preview @50% ${"%.1f".format(passSamples[0])}, restore @50% ${"%.1f".format(passSamples[1])}; " +
                "holding entities+DTOs would retain ${"%.1f".format(held)} MB",
        )
        // The heap figures above are printed for a human, not asserted: System.gc()-based numbers are not reliable enough
        // to gate CI. The row-by-row round trip and the timing printout are the checks.
    }

    private companion object {
        const val PINGS = 100_000
        const val SESSIONS = 20
    }
}
