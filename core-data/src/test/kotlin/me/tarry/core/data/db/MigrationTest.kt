package me.tarry.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rule 4 infrastructure: builds the exported v1 schema, then opens it through Room with
 * [ALL_MIGRATIONS] so Room validates the on-disk schema against the entities.
 * When v2 ships, add a test that inserts rows at v1 and runs the 1 -> 2 migration.
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

    @Test
    fun createV1_thenOpenThroughRoomWithAllMigrations_validatesSchema() {
        helper.createDatabase(1).close()

        val db = Room.databaseBuilder(context, TarryDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            // Opening validates the on-disk schema against the entities.
            assertThat(db.openHelper.writableDatabase.version).isEqualTo(1)
        } finally {
            db.close()
        }
    }
}
