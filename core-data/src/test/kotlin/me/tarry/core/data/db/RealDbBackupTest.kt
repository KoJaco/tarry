package me.tarry.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.model.SessionEndReason
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Optional check of snapshot and replace against a real database (a copy pulled from a device). Skipped unless the env
 * var `TARRY_REAL_DB` points at a `tarry.db`. The file (and its `-wal`/`-shm` siblings) is copied to a temp dir first
 * and never modified; a v1 file is migrated in the copy. The database must never be copied into the repo (it is public).
 *
 * Snapshots the copy, replaces an empty database with it, and compares every table row for row. The gzip JSON file
 * round trip over the same data is `RealDbBackupFileTest` in `:app`, same env var.
 */
@RunWith(RobolectricTestRunner::class)
class RealDbBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aRealDatabaseRestoresIntoAnEmptyOneRowForRow() = runBlocking<Unit> {
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
        val realDb = Room.databaseBuilder(context, TarryDatabase::class.java, copy.path)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val empty = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val t0 = System.nanoTime()
            val snapshot = BackupStore(realDb).snapshot()
            val t1 = System.nanoTime()
            BackupStore(empty).replaceAll(snapshot)
            val t2 = System.nanoTime()
            val restored = BackupStore(empty).snapshot()
            println(
                "REAL_DB rows: sessions=${snapshot.sessions.size} pings=${snapshot.pings.size} visits=${snapshot.visits.size} " +
                    "trips=${snapshot.trips.size} places=${snapshot.places.size} jobs=${snapshot.jobs.size} " +
                    "blocks=${snapshot.jobBlocks.size} gaps=${snapshot.gaps.size}; snapshot ${(t1 - t0) / 1_000_000} ms, " +
                    "replace ${(t2 - t1) / 1_000_000} ms",
            )

            // A session still open in the source is closed by the restore (KILLED, no Gap); nothing else differs.
            val lastPing = snapshot.pings.groupBy { it.sessionId }.mapValues { (_, p) -> p.maxOf { it.timestamp } }
            val expected = snapshot.copy(
                sessions = snapshot.sessions.map {
                    if (it.end == null) it.copy(end = lastPing[it.id] ?: it.start, endReason = SessionEndReason.KILLED) else it
                },
            )
            assertThat(restored.sessions).isEqualTo(expected.sessions)
            assertThat(restored.pings).isEqualTo(expected.pings)
            assertThat(restored.jobs).isEqualTo(expected.jobs)
            assertThat(restored.places).isEqualTo(expected.places)
            assertThat(restored.visits).isEqualTo(expected.visits)
            assertThat(restored.trips).isEqualTo(expected.trips)
            assertThat(restored.jobBlocks).isEqualTo(expected.jobBlocks)
            assertThat(restored.excludedRanges).isEqualTo(expected.excludedRanges)
            assertThat(restored.gaps).isEqualTo(expected.gaps)
            assertThat(restored.scheduleTemplates).isEqualTo(expected.scheduleTemplates)
            assertThat(restored.scheduleOverrides).isEqualTo(expected.scheduleOverrides)
            assertThat(restored.manualOverrides).isEqualTo(expected.manualOverrides)
            assertThat(restored.checkpoints).isEqualTo(expected.checkpoints)
            empty.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        } finally {
            realDb.close()
            empty.close()
        }
    }
}
