package me.tarry.app.export.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import me.tarry.core.data.db.BackupSnapshot
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/** Why a backup or restore did not happen; each maps to one user-facing message. */
enum class BackupError { CANNOT_OPEN, NOT_A_BACKUP, TOO_NEW, DAMAGED, WRITE_FAILED, RESTORE_FAILED }

class BackupException(val error: BackupError, cause: Throwable? = null) : Exception(error.name, cause)

/** Just the identifying fields, so a file with a newer or broken body can still be classified. */
@Serializable
private data class BackupHeader(val format: String = "", val formatVersion: Int = 0)

/**
 * Gzip-compressed JSON backup files. Encode and decode stream through the gzip streams, so no whole-file string is
 * built (about 100k locations at the 90-day retention).
 */
@OptIn(ExperimentalSerializationApi::class)
object BackupCodec {
    const val FORMAT = "tarry-backup"
    const val CURRENT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true // a later version may add fields
        explicitNulls = false
        encodeDefaults = true
    }

    /** Writes [file] gzip-compressed to [out] and finishes the gzip stream. [out] stays open for the caller to close. */
    fun encode(file: BackupFile, out: OutputStream) {
        val gzip = GZIPOutputStream(out.buffered())
        json.encodeToStream(file, gzip)
        gzip.finish()
        gzip.flush()
    }

    /**
     * Reads, upgrades and checks a backup. [open] is called once, or twice when the body fails to decode and the
     * header is re-read to say why. Throws [BackupException]: [BackupError.NOT_A_BACKUP] (not gzip, not JSON, wrong
     * `format`), [BackupError.TOO_NEW], [BackupError.DAMAGED] (truncated, corrupt or inconsistent).
     */
    fun decode(open: () -> InputStream): BackupFile {
        val file = try {
            gzipped(open).use { json.decodeFromStream<BackupFile>(it) }
        } catch (e: BackupException) {
            throw e
        } catch (e: SerializationException) {
            throw classify(open, e)
        } catch (e: IllegalArgumentException) {
            throw classify(open, e)
        } catch (e: IOException) {
            throw BackupException(BackupError.DAMAGED, e)
        }
        checkHeader(file.format, file.formatVersion)
        return upgrade(file)
    }

    /** Opens the gzip layer; an empty or non-gzip file is "not a Tarry backup", any other open failure "cannot open". */
    private fun gzipped(open: () -> InputStream): InputStream {
        val raw = try {
            open()
        } catch (e: IOException) {
            throw BackupException(BackupError.CANNOT_OPEN, e)
        }
        return try {
            GZIPInputStream(raw.buffered())
        } catch (e: ZipException) {
            raw.close()
            throw BackupException(BackupError.NOT_A_BACKUP, e)
        } catch (e: EOFException) {
            raw.close()
            throw BackupException(BackupError.NOT_A_BACKUP, e)
        } catch (e: IOException) {
            raw.close()
            throw BackupException(BackupError.CANNOT_OPEN, e)
        }
    }

    /** The body failed to decode: look at the header alone to tell a newer or foreign file from a damaged one. */
    private fun classify(open: () -> InputStream, cause: Throwable): BackupException {
        val header = try {
            gzipped(open).use { json.decodeFromStream<BackupHeader>(it) }
        } catch (e: BackupException) {
            return e
        } catch (e: Exception) {
            return BackupException(BackupError.NOT_A_BACKUP, cause)
        }
        return try {
            checkHeader(header.format, header.formatVersion)
            BackupException(BackupError.DAMAGED, cause)
        } catch (e: BackupException) {
            e
        }
    }

    private fun checkHeader(format: String, version: Int) {
        if (format != FORMAT || version < 1) throw BackupException(BackupError.NOT_A_BACKUP)
        if (version > CURRENT_VERSION) throw BackupException(BackupError.TOO_NEW)
    }

    /**
     * Steps an older file up to [CURRENT_VERSION], one `upgrade(vN to vN+1)` at a time. There is none yet: version 1
     * is the first. A breaking change adds its step here, with a test.
     */
    fun upgrade(file: BackupFile): BackupFile {
        var f = file
        while (f.formatVersion < CURRENT_VERSION) {
            f = when (f.formatVersion) {
                else -> throw BackupException(BackupError.NOT_A_BACKUP)
            }
        }
        return f
    }

    /** Referential and uniqueness checks, then the entities. Nothing has been written when this throws. */
    fun validate(file: BackupFile): BackupSnapshot {
        val t = file.tables
        fun unique(ids: List<Any>) {
            if (ids.size != ids.toSet().size) throw BackupException(BackupError.DAMAGED)
        }
        unique(t.sessions.map { it.id })
        unique(t.pings.map { it.id })
        unique(t.jobs.map { it.id })
        unique(t.places.map { it.id })
        unique(t.visits.map { it.id })
        unique(t.trips.map { it.id })
        unique(t.jobBlocks.map { it.id })
        unique(t.excludedRanges.map { it.id })
        unique(t.gaps.map { it.id })
        unique(t.scheduleTemplates.map { it.id })
        unique(t.scheduleOverrides.map { it.weekStart })
        unique(t.manualOverrides.map { it.id })
        unique(t.checkpoints.map { it.id })
        val sessionIds = t.sessions.mapTo(HashSet()) { it.id }
        if (t.pings.any { it.sessionId !in sessionIds }) throw BackupException(BackupError.DAMAGED)
        val jobIds = t.jobs.mapTo(HashSet()) { it.id }
        if (t.jobBlocks.any { it.jobId !in jobIds }) throw BackupException(BackupError.DAMAGED)
        return try {
            t.toSnapshot()
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.DAMAGED, e)
        }
    }
}
