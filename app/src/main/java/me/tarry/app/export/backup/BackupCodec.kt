package me.tarry.app.export.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeToSequence
import me.tarry.core.data.db.BackupSnapshot
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/** Why a backup or restore did not happen; each maps to one user-facing message. */
enum class BackupError { CANNOT_OPEN, NOT_A_BACKUP, TOO_NEW, DAMAGED, WRITE_FAILED, RESTORE_FAILED, DELETE_FAILED }

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

    /** Largest number of pings [read] hands to its callback at once. */
    const val PING_CHUNK = 500

    /**
     * Writes a file as a gzip stream of newline-separated JSON values: [writeHead] once (the [BackupFile] with no pings),
     * then [writePing] per ping, then [finish]. Nothing is accumulated, so memory stays flat. [out] stays open for the
     * caller to close; call [Writer.close] (or use it) so the deflater is freed on failure paths.
     */
    class Writer internal constructor(out: OutputStream) : java.io.Closeable {
        private val gzip = object : GZIPOutputStream(out.buffered()) {
            /** Frees the native Deflater without touching the stream (safe to call twice). */
            fun endDeflater() = def.end()
        }
        private val sink = gzip.buffered(32 * 1024)
        private var headWritten = false

        fun writeHead(file: BackupFile) {
            check(!headWritten) { "head already written" }
            require(file.tables.pings.isEmpty()) { "pings go through writePing" }
            headWritten = true
            line(json.encodeToString(BackupFile.serializer(), file))
        }

        fun writePing(ping: PingDto) {
            check(headWritten) { "head first" }
            line(json.encodeToString(PingDto.serializer(), ping))
        }

        /** Flushes and finishes the gzip stream (writes the trailer that detects truncation), then frees the deflater. */
        fun finish() {
            try {
                sink.flush()
                gzip.finish()
                gzip.flush()
            } finally {
                gzip.endDeflater()
            }
        }

        /** Frees the deflater on a failure path, where [finish] was not reached. Does not close the underlying stream. */
        override fun close() = gzip.endDeflater()

        private fun line(text: String) {
            sink.write(text.toByteArray(Charsets.UTF_8))
            sink.write('\n'.code)
        }
    }

    fun writer(out: OutputStream) = Writer(out)

    /** Convenience for small files and tests: head, then every ping in `file.tables.pings`, then finish. */
    fun encode(file: BackupFile, out: OutputStream) {
        val w = writer(out)
        w.writeHead(file.copy(tables = file.tables.copy(pings = emptyList())))
        file.tables.pings.forEach(w::writePing)
        w.finish()
    }

    /** Thrown by a callback; carried through [read]'s error mapping untouched. */
    private class CallbackFailure(cause: Throwable) : Exception(cause)

    /**
     * Streams a backup once: reads, upgrades and checks the header, calls [onHeader] with it (pings emptied), then hands
     * every ping to [onPings] in chunks of at most [PING_CHUNK], and returns the header. A file whose header still
     * carries inline `tables.pings` (the earlier single-object layout) feeds them first, through [onInline] when given
     * (their ids are in no particular order), else through [onPings].
     * [open] is called once, or twice when the first value fails to decode and the header is re-read to say why.
     * Throws [BackupException]: [BackupError.NOT_A_BACKUP] (not gzip, not JSON, wrong `format`), [BackupError.TOO_NEW],
     * [BackupError.DAMAGED] (truncated, corrupt or inconsistent), [BackupError.CANNOT_OPEN]. What [onHeader] or
     * [onPings] throw is rethrown as is.
     */
    suspend fun read(
        open: () -> InputStream,
        onHeader: suspend (BackupFile) -> Unit = {},
        onInline: (suspend (List<PingDto>) -> Unit)? = null,
        onPings: suspend (List<PingDto>) -> Unit,
    ): BackupFile {
        var headerRead = false
        try {
            gzipped(open).use { stream ->
                val values = json.decodeToSequence<JsonElement>(stream, DecodeSequenceMode.WHITESPACE_SEPARATED).iterator()
                if (!values.hasNext()) throw BackupException(BackupError.NOT_A_BACKUP)
                val raw = try {
                    json.decodeFromJsonElement<BackupFile>(values.next())
                } catch (e: SerializationException) {
                    throw classify(open, e)
                } catch (e: IllegalArgumentException) {
                    throw classify(open, e)
                }
                checkHeader(raw.format, raw.formatVersion)
                val inline = raw.tables.pings
                val header = upgrade(raw).let { it.copy(tables = it.tables.copy(pings = emptyList())) }
                headerRead = true
                callback { onHeader(header) }
                val chunk = ArrayList<PingDto>(PING_CHUNK)
                suspend fun add(p: PingDto) {
                    chunk += p
                    if (chunk.size == PING_CHUNK) {
                        callback { onPings(chunk.toList()) }
                        chunk.clear()
                    }
                }
                if (inline.isNotEmpty() && onInline != null) {
                    for (part in inline.chunked(PING_CHUNK)) callback { onInline(part) }
                } else {
                    for (p in inline) add(p)
                }
                while (values.hasNext()) add(json.decodeFromJsonElement<PingDto>(values.next()))
                if (chunk.isNotEmpty()) callback { onPings(chunk.toList()) }
                return header
            }
        } catch (e: CallbackFailure) {
            throw e.cause!!
        } catch (e: BackupException) {
            throw e
        } catch (e: SerializationException) {
            throw BackupException(if (headerRead) BackupError.DAMAGED else classifyAfterFailure(open, e), e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(if (headerRead) BackupError.DAMAGED else classifyAfterFailure(open, e), e)
        } catch (e: EOFException) {
            throw BackupException(BackupError.DAMAGED, e) // truncated
        } catch (e: ZipException) {
            throw BackupException(BackupError.DAMAGED, e) // corrupt gzip body or trailer
        } catch (e: IOException) {
            throw BackupException(BackupError.CANNOT_OPEN, e) // the provider failed mid-read
        }
    }

    private suspend fun callback(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            throw CallbackFailure(e)
        }
    }

    /** Before the header was read: the first value is not JSON at all; tell a cut-off or corrupt Tarry file from a foreign one. */
    private fun classifyAfterFailure(open: () -> InputStream, cause: Throwable): BackupError =
        classify(open, cause).error

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
            gzipped(open).use { stream ->
                // Only the first value: the rest of the file may be ping lines (or broken).
                val first = json.decodeToSequence<JsonElement>(stream, DecodeSequenceMode.WHITESPACE_SEPARATED).first()
                json.decodeFromJsonElement<BackupHeader>(first)
            }
        } catch (e: BackupException) {
            return e
        } catch (e: Exception) {
            // Not even the header parses. If the start still says it is ours, the file is cut short or corrupt.
            return BackupException(if (looksLikeTarry(open)) BackupError.DAMAGED else BackupError.NOT_A_BACKUP, cause)
        }
        return try {
            checkHeader(header.format, header.formatVersion)
            BackupException(BackupError.DAMAGED, cause)
        } catch (e: BackupException) {
            e
        }
    }

    /** The first bytes of the decompressed file carry our `format` marker (we write it first). */
    private fun looksLikeTarry(open: () -> InputStream): Boolean = try {
        gzipped(open).use { stream ->
            val buf = ByteArray(SNIFF_BYTES)
            var n = 0
            while (n < buf.size) {
                val r = stream.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            MARKER.containsMatchIn(String(buf, 0, n, Charsets.UTF_8))
        }
    } catch (e: Exception) {
        false
    }

    private val MARKER = Regex("\"format\"\\s*:\\s*\"$FORMAT\"")
    private const val SNIFF_BYTES = 4096

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

    /**
     * Per-chunk ping checks, run in the preview pass and again in the apply pass: the session exists, the ping converts to
     * an entity, and the id is unique. Streamed pings are written in ascending id order, so for them a strictly increasing
     * id is the uniqueness check and no set of all ids is kept; only the ids of inline pings (the earlier layout, in any
     * order) are held in a set, and a streamed id may not repeat one. Throws [BackupError.DAMAGED]. Use a fresh instance
     * per pass.
     */
    class PingChecker(private val sessionIds: Set<Long>) {
        private val inlineIds = HashSet<Long>()
        private var lastStreamed = Long.MIN_VALUE

        fun inline(chunk: List<PingDto>) {
            for (p in chunk) {
                if (!inlineIds.add(p.id)) throw BackupException(BackupError.DAMAGED)
                common(p)
            }
        }

        fun streamed(chunk: List<PingDto>) {
            for (p in chunk) {
                if (p.id <= lastStreamed || p.id in inlineIds) throw BackupException(BackupError.DAMAGED)
                lastStreamed = p.id
                common(p)
            }
        }

        private fun common(p: PingDto) {
            if (p.sessionId !in sessionIds) throw BackupException(BackupError.DAMAGED)
            try {
                p.toEntity()
            } catch (e: IllegalArgumentException) {
                throw BackupException(BackupError.DAMAGED, e)
            }
        }
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
