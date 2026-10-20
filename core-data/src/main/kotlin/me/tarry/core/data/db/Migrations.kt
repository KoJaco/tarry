package me.tarry.core.data.db

import androidx.room.migration.Migration

/**
 * Manual migrations (AGENTS.md rule 4). Empty at v1. Prefer `@AutoMigration` on the
 * database; add a manual [Migration] here only for backfills or table rebuilds.
 */
val ALL_MIGRATIONS: Array<Migration> = emptyArray()
