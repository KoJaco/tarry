package me.tarry.app.export.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.db.ALL_MIGRATIONS
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.LocationPing
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Optional: the gzip JSON file round trip over a real database copy. Skipped unless env `TARRY_REAL_DB` points at a
 * `tarry.db` (copied to a temp dir first, never modified; the backup file is written to the same temp dir and deleted
 * after). Real data never goes into the repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RealDbBackupFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aRealDatabaseSurvivesTheBackupFileUnchanged() = runBlocking<Unit> {
        val source = System.getenv("TARRY_REAL_DB")
        assumeTrue("TARRY_REAL_DB not set", !source.isNullOrBlank())
        val original = File(source!!)
        assumeTrue("TARRY_REAL_DB does not exist", original.isFile)
        val dir = tmp.newFolder("real-db")
        val copy = File(dir, "tarry.db")
        original.copyTo(copy)
        for (suffix in listOf("-wal", "-shm")) {
            val sibling = File(original.path + suffix)
            if (sibling.isFile) sibling.copyTo(File(dir, "tarry.db$suffix"))
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, TarryDatabase::class.java, copy.path)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val store = BackupStore(db)
            val snapshot = store.snapshot()
            val head = store.readHead()
            val file = File(dir, "backup.json.gz")
            val t0 = System.nanoTime()
            file.outputStream().use { raw ->
                val writer = BackupCodec.writer(raw)
                writer.writeHead(fileOf(head.snapshot))
                var after = 0L
                while (true) {
                    val page = store.pingsPage(after, head.maxPingId, 1000)
                    page.forEach { writer.writePing(it.toDto()) }
                    if (page.size < 1000) break
                    after = page.last().id
                }
                writer.finish()
            }
            val t1 = System.nanoTime()
            val pings = mutableListOf<LocationPing>()
            val header = BackupCodec.read({ file.inputStream() }) { chunk -> pings += chunk.map { it.toEntity() } }
            val decoded = BackupCodec.validate(header).copy(pings = pings)
            val t2 = System.nanoTime()
            println("REAL_DB file: ${file.length()} bytes, ${pings.size} pings, encode ${(t1 - t0) / 1_000_000} ms, decode ${(t2 - t1) / 1_000_000} ms")
            assertThat(decoded.sessions).isEqualTo(snapshot.sessions)
            assertThat(decoded.pings).isEqualTo(snapshot.pings)
            assertThat(decoded.jobs).isEqualTo(snapshot.jobs)
            assertThat(decoded.places).isEqualTo(snapshot.places)
            assertThat(decoded.visits).isEqualTo(snapshot.visits)
            assertThat(decoded.trips).isEqualTo(snapshot.trips)
            assertThat(decoded.jobBlocks).isEqualTo(snapshot.jobBlocks)
            assertThat(decoded.excludedRanges).isEqualTo(snapshot.excludedRanges)
            assertThat(decoded.gaps).isEqualTo(snapshot.gaps)
            assertThat(decoded.scheduleTemplates).isEqualTo(snapshot.scheduleTemplates)
            assertThat(decoded.scheduleOverrides).isEqualTo(snapshot.scheduleOverrides)
            assertThat(decoded.manualOverrides).isEqualTo(snapshot.manualOverrides)
            assertThat(decoded.checkpoints).isEqualTo(snapshot.checkpoints)
        } finally {
            db.close()
        }
    }
}
