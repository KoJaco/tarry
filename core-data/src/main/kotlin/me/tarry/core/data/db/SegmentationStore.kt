package me.tarry.core.data.db

import androidx.room.withTransaction

/**
 * Runs a block as one Room transaction so segmentation reconciles its reads and writes atomically.
 * A thin wrapper so `:app` does not depend on the database class or room-ktx.
 */
class SegmentationStore(private val db: TarryDatabase) {
    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}
