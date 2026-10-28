package me.tarry.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Optional check of the 1 -> 2 migration against a real v1 database (a backup pulled from a device). Skipped unless
 * the env var `TARRY_REAL_DB` points at a v1 `tarry.db`. The file (and its `-wal`/`-shm` siblings) is copied to a temp
 * dir first and never modified. The database must never be copied into the repo (it is public).
 */
@RunWith(RobolectricTestRunner::class)
class RealDbMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val tables = listOf("visit", "trip", "place", "job", "tracking_session", "location_ping", "gap")

    private fun SQLiteDatabase.count(sql: String): Long = rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun migratesARealV1DatabaseWithoutLosingRows() {
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

        // Count v1 rows with plain SQLite (opening it also replays the WAL into the copy).
        val before: Map<String, Long>
        val expectedBlocks: Long
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { v1 ->
            assertThat(v1.version).isEqualTo(1)
            before = tables.associateWith { v1.count("SELECT COUNT(*) FROM $it") }
            val liveJob = "EXISTS (SELECT 1 FROM job j WHERE j.id = %s AND j.deleted = 0)"
            expectedBlocks = v1.count("SELECT COUNT(*) FROM visit WHERE deleted = 0 AND jobId IS NOT NULL AND " + liveJob.format("visit.jobId")) +
                v1.count(
                    "SELECT COUNT(*) FROM visit v JOIN place p ON p.id = v.placeId WHERE v.deleted = 0 AND v.jobId IS NULL " +
                        "AND p.deleted = 0 AND p.ignored = 0 AND p.defaultJobId IS NOT NULL AND " + liveJob.format("p.defaultJobId"),
                ) +
                v1.count("SELECT COUNT(*) FROM trip WHERE deleted = 0 AND attributedJobId IS NOT NULL AND " + liveJob.format("trip.attributedJobId"))
        }
        println("REAL_DB v1 counts: $before, expected job_block rows: $expectedBlocks")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, TarryDatabase::class.java, copy.path)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening runs the migration and validates the schema against the entities.
            val sql = db.openHelper.writableDatabase
            fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
            assertThat(sql.version).isEqualTo(2)
            val after = tables.associateWith { count(it) }
            println("REAL_DB v2 counts: $after, job_block rows: ${count("job_block")}")
            assertThat(after).isEqualTo(before)
            assertThat(count("job_block")).isEqualTo(expectedBlocks)
            sql.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        } finally {
            db.close()
        }
    }
}
