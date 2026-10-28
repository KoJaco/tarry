package me.tarry.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2 (phase 6b): job blocks replace per-segment job, billable, attribution and tags.
 *
 * 1. Tags are folded into `freeNotes` as a `Tags: Group:Tag, ...` line, so no annotation text is lost.
 * 2. Each live visit with a `jobId`, and each live trip with an `attributedJobId`, becomes a `job_block` over its span.
 *    Blocks are only made for jobs that still exist (a dangling id would violate the block's foreign key and fail the
 *    upgrade); jobs are soft-deleted, so in practice none are skipped.
 * 3. `job.defaultBillable` is added (existing jobs default to billable).
 * 4. The tag tables are dropped.
 * 5. visit, trip and place are rebuilt without the removed columns (minSdk 29 has no `DROP COLUMN`). By then no table
 *    references them (the tag tables are gone), so the rebuild needs no foreign-key handling.
 *
 * The visit/trip/place `CREATE TABLE` statements must match `schemas/.../2.json` exactly (Room validates them on open).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        foldTagsIntoNotes(db, itemTable = "visit", linkTable = "visit_tag", linkColumn = "visitId")
        foldTagsIntoNotes(db, itemTable = "trip", linkTable = "trip_tag", linkColumn = "tripId")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `job_block` (`id` TEXT NOT NULL, `jobId` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                "`end` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `billable` INTEGER NOT NULL, `notes` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`jobId`) REFERENCES `job`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_block_start` ON `job_block` (`start`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_block_jobId` ON `job_block` (`jobId`)")
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO job_block (id, jobId, start, `end`, zoneId, billable, notes, updatedAt, deleted) " +
                "SELECT $UUID_SQL, v.jobId, v.start, v.`end`, v.zoneId, COALESCE(v.billable, p.defaultBillable, 0), NULL, ?, 0 " +
                "FROM visit v LEFT JOIN place p ON p.id = v.placeId " +
                "WHERE v.deleted = 0 AND v.jobId IS NOT NULL AND EXISTS (SELECT 1 FROM job j WHERE j.id = v.jobId)",
            arrayOf<Any?>(now),
        )
        db.execSQL(
            "INSERT INTO job_block (id, jobId, start, `end`, zoneId, billable, notes, updatedAt, deleted) " +
                "SELECT $UUID_SQL, t.attributedJobId, t.start, t.`end`, t.zoneId, COALESCE(t.billable, 0), NULL, ?, 0 " +
                "FROM trip t " +
                "WHERE t.deleted = 0 AND t.attributedJobId IS NOT NULL AND EXISTS (SELECT 1 FROM job j WHERE j.id = t.attributedJobId)",
            arrayOf<Any?>(now),
        )

        db.execSQL("ALTER TABLE `job` ADD COLUMN `defaultBillable` INTEGER NOT NULL DEFAULT 1")

        listOf("place_default_tag", "visit_tag", "trip_tag", "tag", "tag_group").forEach { db.execSQL("DROP TABLE `$it`") }

        rebuild(
            db, "place",
            createSql = "CREATE TABLE `place` (`id` TEXT NOT NULL, `centroidLat` REAL NOT NULL, `centroidLng` REAL NOT NULL, " +
                "`radiusM` REAL NOT NULL DEFAULT 75, `address` TEXT, `name` TEXT, `defaultJobId` TEXT, " +
                "`ignored` INTEGER NOT NULL DEFAULT 0, `mergedIntoId` TEXT, `lastVisitAt` INTEGER, " +
                "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            columns = "id, centroidLat, centroidLng, radiusM, address, name, defaultJobId, ignored, mergedIntoId, lastVisitAt, updatedAt, deleted",
            indexes = listOf("CREATE INDEX IF NOT EXISTS `index_place_lastVisitAt` ON `place` (`lastVisitAt`)"),
        )
        rebuild(
            db, "visit",
            createSql = "CREATE TABLE `visit` (`id` TEXT NOT NULL, `placeId` TEXT, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, " +
                "`zoneId` TEXT NOT NULL, `freeNotes` TEXT NOT NULL DEFAULT '', `frozen` INTEGER NOT NULL DEFAULT 0, " +
                "`origin` TEXT NOT NULL, `confidence` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            columns = "id, placeId, start, `end`, zoneId, freeNotes, frozen, origin, confidence, updatedAt, deleted",
            indexes = listOf(
                "CREATE INDEX IF NOT EXISTS `index_visit_start` ON `visit` (`start`)",
                "CREATE INDEX IF NOT EXISTS `index_visit_placeId` ON `visit` (`placeId`)",
            ),
        )
        rebuild(
            db, "trip",
            createSql = "CREATE TABLE `trip` (`id` TEXT NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, " +
                "`zoneId` TEXT NOT NULL, `fromVisitId` TEXT, `toVisitId` TEXT, `distanceM` REAL NOT NULL, `mode` TEXT NOT NULL, " +
                "`freeNotes` TEXT NOT NULL DEFAULT '', `frozen` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            columns = "id, start, `end`, zoneId, fromVisitId, toVisitId, distanceM, mode, freeNotes, frozen, updatedAt, deleted",
            indexes = listOf("CREATE INDEX IF NOT EXISTS `index_trip_start` ON `trip` (`start`)"),
        )
    }
}

/** Every migration, in order; passed to the Room builder. Declared after the migrations (top-level initialisation order). */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

/** A random lowercase 8-4-4-4-12 version-4 UUID, evaluated per row. */
private const val UUID_SQL =
    "(lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || " +
        "substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))))"

/** Appends `Tags: Group:Tag, Group:Tag` (groups then tags in their sort order) to each item's notes, on a new line when notes exist. */
private fun foldTagsIntoNotes(db: SupportSQLiteDatabase, itemTable: String, linkTable: String, linkColumn: String) {
    val lines = LinkedHashMap<String, MutableList<String>>()
    db.query(
        "SELECT l.$linkColumn, g.name, t.name FROM $linkTable l JOIN tag t ON t.id = l.tagId JOIN tag_group g ON g.id = t.groupId " +
            "JOIN $itemTable i ON i.id = l.$linkColumn ORDER BY l.$linkColumn, g.sortOrder, t.sortOrder, g.name, t.name",
    ).use { c ->
        while (c.moveToNext()) lines.getOrPut(c.getString(0)) { mutableListOf() }.add("${c.getString(1)}:${c.getString(2)}")
    }
    lines.forEach { (id, names) ->
        val line = "Tags: " + names.joinToString(", ")
        db.execSQL(
            "UPDATE $itemTable SET freeNotes = CASE WHEN freeNotes = '' THEN ? ELSE freeNotes || char(10) || ? END WHERE id = ?",
            arrayOf<Any?>(line, line, id),
        )
    }
}

private fun rebuild(db: SupportSQLiteDatabase, table: String, createSql: String, columns: String, indexes: List<String>) {
    db.execSQL(createSql.replaceFirst("`$table`", "`${table}_new`"))
    db.execSQL("INSERT INTO `${table}_new` ($columns) SELECT $columns FROM `$table`")
    db.execSQL("DROP TABLE `$table`")
    db.execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
    indexes.forEach { db.execSQL(it) }
}
