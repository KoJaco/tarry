package me.tarry.core.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rule 4: seeds a v1 database with representative rows (tags, annotated, frozen and soft-deleted visits, trips,
 * place defaults), runs [MIGRATION_1_2] through [MigrationTestHelper] (which validates the result against 2.json),
 * and asserts the data survives.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = context.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        dbFile,
        AndroidSQLiteDriver(),
        TarryDatabase::class,
    )

    private fun SQLiteConnection.longs(sql: String): List<Long> =
        prepare(sql).use { st -> buildList { while (st.step()) add(st.getLong(0)) } }

    private fun SQLiteConnection.strings(sql: String): List<String> =
        prepare(sql).use { st -> buildList { while (st.step()) add(st.getText(0)) } }

    private fun SQLiteConnection.count(table: String) = longs("SELECT COUNT(*) FROM $table").single()

    private fun q(s: String?) = s?.let { "'$it'" } ?: "NULL"

    private fun visit(
        id: String, start: Long, jobId: String?, billable: Int?, notes: String,
        placeId: String? = "p1", deleted: Int = 0, frozen: Int = 0,
    ) = "INSERT INTO visit (id, placeId, start, `end`, zoneId, jobId, freeNotes, billable, frozen, origin, confidence, updatedAt, deleted) " +
        "VALUES ('$id', ${q(placeId)}, $start, ${start + 1000}, 'Australia/Sydney', ${q(jobId)}, '$notes', ${billable ?: "NULL"}, " +
        "$frozen, 'DETECTED', 0.9, 5, $deleted)"

    private fun trip(id: String, start: Long, attributedJobId: String?, billable: Int?, notes: String = "") =
        "INSERT INTO trip (id, start, `end`, zoneId, fromVisitId, toVisitId, distanceM, mode, billable, attributedJobId, freeNotes, frozen, updatedAt, deleted) " +
            "VALUES ('$id', $start, ${start + 500}, 'UTC', 'v1', 'v2', 1234.5, 'VEHICLE', ${billable ?: "NULL"}, ${q(attributedJobId)}, '$notes', 1, 6, 0)"

    private fun seedV1(db: SQLiteConnection) {
        db.execSQL("INSERT INTO job (id, name, colorArgb, archived, updatedAt, deleted) VALUES ('j1', 'Smith reno', 1, 0, 1, 0)")
        db.execSQL("INSERT INTO job (id, name, colorArgb, archived, updatedAt, deleted) VALUES ('j2', 'Archived', 2, 1, 1, 0)")
        db.execSQL(
            "INSERT INTO place (id, centroidLat, centroidLng, radiusM, address, name, defaultJobId, defaultBillable, ignored, mergedIntoId, lastVisitAt, updatedAt, deleted) " +
                "VALUES ('p1', -33.5, 151.2, 80, '1 Main St', 'Site', 'j1', 1, 0, NULL, 999, 7, 0)",
        )
        db.execSQL(
            "INSERT INTO place (id, centroidLat, centroidLng, radiusM, address, name, defaultJobId, defaultBillable, ignored, mergedIntoId, lastVisitAt, updatedAt, deleted) " +
                "VALUES ('p2', -33.6, 151.3, 75, NULL, NULL, NULL, NULL, 1, NULL, NULL, 8, 1)",
        )

        // Group "Work" sorts before "Client"; tags inside a group sort by sortOrder, not name.
        db.execSQL("INSERT INTO tag_group (id, name, colorArgb, sortOrder, updatedAt, deleted) VALUES ('g1', 'Work', 0, 0, 1, 0)")
        db.execSQL("INSERT INTO tag_group (id, name, colorArgb, sortOrder, updatedAt, deleted) VALUES ('g2', 'Client', 0, 1, 1, 0)")
        db.execSQL("INSERT INTO tag (id, groupId, name, sortOrder, archived, updatedAt, deleted) VALUES ('t1', 'g1', 'Urgent', 1, 0, 1, 0)")
        db.execSQL("INSERT INTO tag (id, groupId, name, sortOrder, archived, updatedAt, deleted) VALUES ('t2', 'g1', 'Install', 0, 0, 1, 0)")
        db.execSQL("INSERT INTO tag (id, groupId, name, sortOrder, archived, updatedAt, deleted) VALUES ('t3', 'g2', 'Smith', 0, 0, 1, 0)")
        db.execSQL("INSERT INTO place_default_tag (placeId, tagId) VALUES ('p1', 't1')")

        // v1: tags + existing notes. v2: tags, no notes. v3: frozen, job, billable null, place default true.
        // v4: job, billable false. v5: deleted with a job. v6: frozen, no job, no tags, at a deleted place.
        db.execSQL(visit("v1", 1_000_000, null, null, "Existing note"))
        db.execSQL(visit("v2", 2_000_000, null, null, ""))
        db.execSQL(visit("v3", 3_000_000, "j1", null, "n3", frozen = 1))
        db.execSQL(visit("v4", 4_000_000, "j1", 0, "n4"))
        db.execSQL(visit("v5", 5_000_000, "j1", 1, "", deleted = 1))
        db.execSQL(visit("v6", 6_000_000, null, null, "keep me", placeId = "p2", frozen = 1))
        db.execSQL("INSERT INTO visit_tag (visitId, tagId) VALUES ('v1', 't1')")
        db.execSQL("INSERT INTO visit_tag (visitId, tagId) VALUES ('v1', 't3')")
        db.execSQL("INSERT INTO visit_tag (visitId, tagId) VALUES ('v1', 't2')")
        db.execSQL("INSERT INTO visit_tag (visitId, tagId) VALUES ('v2', 't3')")

        db.execSQL(trip("tr1", 1_500_000, "j1", null, notes = "toll"))
        db.execSQL(trip("tr2", 2_500_000, "j2", 1))
        db.execSQL(trip("tr3", 3_500_000, null, null))
        db.execSQL("INSERT INTO trip_tag (tripId, tagId) VALUES ('tr1', 't2')")
    }

    private data class B(
        val jobId: String, val start: Long, val end: Long, val zone: String, val billable: Long,
        val notes: String?, val updatedAt: Long, val deleted: Long,
    )

    @Test
    fun migrate1To2_foldsTagsIntoNotes_buildsBlocks_dropsOldTablesAndKeepsData() {
        helper.createDatabase(1).also { seedV1(it) }.close()

        val db = helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2))

        // Tags folded into notes: group sortOrder, then tag sortOrder; appended after a newline when notes exist.
        fun notes(table: String, id: String) = db.strings("SELECT freeNotes FROM $table WHERE id = '$id'").single()
        assertThat(notes("visit", "v1")).isEqualTo("Existing note\nTags: Work:Install, Work:Urgent, Client:Smith")
        assertThat(notes("visit", "v2")).isEqualTo("Tags: Client:Smith")
        assertThat(notes("visit", "v3")).isEqualTo("n3")
        assertThat(notes("visit", "v6")).isEqualTo("keep me")
        assertThat(notes("trip", "tr1")).isEqualTo("toll\nTags: Work:Install")
        assertThat(notes("trip", "tr3")).isEmpty()

        // Blocks: tr1, tr2, v3, v4 (v5 is deleted, v1/v2/v6 have no job).
        assertThat(db.count("job_block")).isEqualTo(4)
        val b = db.prepare("SELECT jobId, start, `end`, zoneId, billable, notes, updatedAt, deleted FROM job_block ORDER BY start").use { st ->
            buildList {
                while (st.step()) {
                    add(B(st.getText(0), st.getLong(1), st.getLong(2), st.getText(3), st.getLong(4), if (st.isNull(5)) null else st.getText(5), st.getLong(6), st.getLong(7)))
                }
            }
        }
        val at = b[0].updatedAt
        assertThat(at).isGreaterThan(1_000_000_000_000L)
        assertThat(b).containsExactly(
            B("j1", 1_500_000, 1_500_500, "UTC", 0, null, at, 0), // tr1: trip billable null -> false
            B("j2", 2_500_000, 2_500_500, "UTC", 1, null, at, 0), // tr2: trip billable true
            B("j1", 3_000_000, 3_001_000, "Australia/Sydney", 1, null, at, 0), // v3: visit null, place default true
            B("j1", 4_000_000, 4_001_000, "Australia/Sydney", 0, null, at, 0), // v4: visit billable false
        ).inOrder()

        // Block ids are proper lowercase v4 UUIDs and unique.
        val ids = db.strings("SELECT id FROM job_block")
        assertThat(ids.toSet()).hasSize(4)
        ids.forEach { assertThat(it).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}") }

        // Job gains defaultBillable = 1 for existing rows; other job columns survive.
        assertThat(db.longs("SELECT defaultBillable FROM job ORDER BY id")).containsExactly(1L, 1L).inOrder()
        assertThat(db.strings("SELECT name FROM job ORDER BY id")).containsExactly("Smith reno", "Archived").inOrder()
        assertThat(db.longs("SELECT archived FROM job ORDER BY id")).containsExactly(0L, 1L).inOrder()

        // Old tables are gone.
        val tables = db.strings("SELECT name FROM sqlite_master WHERE type = 'table'")
        assertThat(tables).containsNoneOf("tag", "tag_group", "visit_tag", "trip_tag", "place_default_tag")
        assertThat(tables).contains("job_block")

        // Removed columns are gone, kept columns and row counts are preserved.
        fun columns(t: String) = db.strings("SELECT name FROM pragma_table_info('$t')")
        assertThat(columns("visit")).containsNoneOf("jobId", "billable")
        assertThat(columns("trip")).containsNoneOf("attributedJobId", "billable")
        assertThat(columns("place")).doesNotContain("defaultBillable")
        assertThat(db.count("visit")).isEqualTo(6)
        assertThat(db.count("trip")).isEqualTo(3)
        assertThat(db.count("place")).isEqualTo(2)
        assertThat(
            db.strings(
                "SELECT id || '|' || placeId || '|' || frozen || '|' || deleted || '|' || zoneId || '|' || start || '|' || `end` || '|' || origin || '|' || updatedAt " +
                    "FROM visit WHERE id IN ('v3', 'v5', 'v6') ORDER BY id",
            ),
        ).containsExactly(
            "v3|p1|1|0|Australia/Sydney|3000000|3001000|DETECTED|5",
            "v5|p1|0|1|Australia/Sydney|5000000|5001000|DETECTED|5",
            "v6|p2|1|0|Australia/Sydney|6000000|6001000|DETECTED|5",
        ).inOrder()
        assertThat(
            db.strings("SELECT fromVisitId || '|' || toVisitId || '|' || distanceM || '|' || mode || '|' || frozen || '|' || updatedAt FROM trip WHERE id = 'tr1'"),
        ).containsExactly("v1|v2|1234.5|VEHICLE|1|6")
        assertThat(
            db.strings(
                "SELECT id || '|' || centroidLat || '|' || radiusM || '|' || address || '|' || name || '|' || defaultJobId || '|' || ignored || '|' || lastVisitAt || '|' || updatedAt || '|' || deleted " +
                    "FROM place WHERE id = 'p1'",
            ),
        ).containsExactly("p1|-33.5|80.0|1 Main St|Site|j1|0|999|7|0")
        assertThat(db.longs("SELECT ignored FROM place WHERE id = 'p2'")).containsExactly(1L)
        assertThat(db.longs("SELECT deleted FROM place WHERE id = 'p2'")).containsExactly(1L)

        // Indexes were recreated and no foreign key is violated.
        val indexes = db.strings("SELECT name FROM sqlite_master WHERE type = 'index'")
        assertThat(indexes).containsAtLeast(
            "index_visit_start", "index_visit_placeId", "index_trip_start", "index_place_lastVisitAt",
            "index_job_block_start", "index_job_block_jobId",
        )
        assertThat(indexes).doesNotContain("index_visit_jobId")
        assertThat(db.longs("SELECT COUNT(*) FROM pragma_foreign_key_check")).containsExactly(0L)
        db.close()
    }

    @Test
    fun migrate1To2_withNothingToConvertStillValidates() {
        helper.createDatabase(1).close()

        val db = helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2))

        assertThat(db.count("job_block")).isEqualTo(0)
        db.close()
    }

    @Test
    fun migrate1To2_skipsBlocksForJobsThatNoLongerExist() {
        helper.createDatabase(1).also { db ->
            db.execSQL("INSERT INTO job (id, name, colorArgb, archived, updatedAt, deleted) VALUES ('j1', 'J', 1, 0, 1, 0)")
            db.execSQL(visit("ok", 1000, "j1", 1, "", placeId = null))
            db.execSQL(visit("dangling", 3000, "ghost", 1, "", placeId = null))
        }.close()

        val db = helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2))

        assertThat(db.strings("SELECT jobId FROM job_block")).containsExactly("j1")
        assertThat(db.count("visit")).isEqualTo(2)
        db.close()
    }
}
