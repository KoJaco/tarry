package me.tarry.app.export.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BackupCodecTest {
    private fun decode(bytes: ByteArray) = decodeAll { ByteArrayInputStream(bytes) }

    private fun errorOf(bytes: ByteArray) = assertThrows(BackupException::class.java) { decode(bytes) }.error

    @Test
    fun everyEntityFieldSurvivesAnEncodeDecodeRoundTrip() {
        val snapshot = everythingSnapshot()
        val prefs = mutablePreferencesOf(
            intPreferencesKey("i") to 3,
            longPreferencesKey("l") to 4_000_000_000L,
            booleanPreferencesKey("b") to true,
            stringPreferencesKey("s") to "x|y;z",
            stringSetPreferencesKey("set") to setOf("b", "a"),
        )
        val file = fileOf(snapshot).copy(settings = prefs.toEntries(), declinedSuggestions = prefs.toEntries())

        val decoded = decode(encodeFile(file))

        assertThat(decoded).isEqualTo(file)
        assertThat(BackupCodec.validate(decoded)).isEqualTo(snapshot)
        assertThat(decoded.settings.toPreferences()).isEqualTo(prefs)
    }

    @Test
    fun nullsAreOmittedAndComeBackAsNull() {
        val bytes = encodeFile(fileOf(everythingSnapshot()))
        val ping = BackupCodec.validate(decode(bytes)).pings.single { it.id == 11L }
        assertThat(ping.speedMps).isNull()
        assertThat(ping.bearing).isNull()
        assertThat(ping.activity).isNull()
    }

    @Test
    fun aV1FileMissingLaterOptionalFieldsStillDecodes() {
        val raw = javaClass.getResourceAsStream("/backup/v1-minimal.json")!!.readBytes()

        val file = decode(gzip(raw))
        val snapshot = BackupCodec.validate(file)

        assertThat(file.formatVersion).isEqualTo(1)
        assertThat(snapshot.sessions.single().endReason).isEqualTo(SessionEndReason.SCHEDULE_END)
        val ping = snapshot.pings.single()
        assertThat(ping.speedMps).isNull()
        assertThat(ping.activity).isNull()
        val visit = snapshot.visits.single()
        assertThat(visit.freeNotes).isEmpty()
        assertThat(visit.frozen).isFalse()
        assertThat(visit.deleted).isFalse()
        assertThat(snapshot.jobs.single().defaultBillable).isTrue()
        assertThat(snapshot.places).isEmpty()
        assertThat(file.settings.map { it.key }).containsExactly("theme", "stay_geofence_radius_m")
    }

    @Test
    fun aNewerFormatVersionIsRejectedEvenWhenItsBodyHasADifferentShape() {
        val newer = gzip("""{"format":"tarry-backup","formatVersion":2,"tables":{"pings":"not a list any more"}}""")
        assertThat(errorOf(newer)).isEqualTo(BackupError.TOO_NEW)

        val newerWellFormed = encodeFile(fileOf(everythingSnapshot(), version = BackupCodec.CURRENT_VERSION + 1))
        assertThat(errorOf(newerWellFormed)).isEqualTo(BackupError.TOO_NEW)
    }

    @Test
    fun aFileThatIsNotGzipIsRejected() {
        assertThat(errorOf("""{"format":"tarry-backup","formatVersion":1}""".toByteArray())).isEqualTo(BackupError.NOT_A_BACKUP)
        assertThat(errorOf(ByteArray(0))).isEqualTo(BackupError.NOT_A_BACKUP)
    }

    @Test
    fun gzippedJsonThatIsNotATarryBackupIsRejected() {
        assertThat(errorOf(gzip("""{"hello":"world"}"""))).isEqualTo(BackupError.NOT_A_BACKUP)
        assertThat(errorOf(gzip("""{"format":"other","formatVersion":1}"""))).isEqualTo(BackupError.NOT_A_BACKUP)
        assertThat(errorOf(gzip("[1,2,3]"))).isEqualTo(BackupError.NOT_A_BACKUP)
        assertThat(errorOf(gzip("plain text"))).isEqualTo(BackupError.NOT_A_BACKUP)
    }

    @Test
    fun aTruncatedBackupIsDamaged() {
        val bytes = encodeFile(fileOf(everythingSnapshot()))
        assertThat(errorOf(bytes.copyOf(bytes.size / 2))).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun aTarryBackupWithABrokenBodyIsDamaged() {
        val broken = gzip("""{"format":"tarry-backup","formatVersion":1,"tables":{"pings":"oops"}}""")
        assertThat(errorOf(broken)).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun aFileThatCannotBeOpenedIsReportedAsSuch() {
        val e = assertThrows(BackupException::class.java) { decodeAll { throw IOException("no") } }
        assertThat(e.error).isEqualTo(BackupError.CANNOT_OPEN)
    }

    @Test
    fun aPingWhoseSessionIsMissingIsDamaged() {
        val s = everythingSnapshot()
        val file = fileOf(s.copy(pings = s.pings + s.pings.first().copy(id = 99, sessionId = 404)))
        val e = assertThrows(BackupException::class.java) { BackupCodec.validate(decode(encodeFile(file))) }
        assertThat(e.error).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun aJobBlockWhoseJobIsMissingIsDamaged() {
        val s = everythingSnapshot()
        val file = fileOf(s.copy(jobBlocks = s.jobBlocks.map { it.copy(jobId = "nope") }))
        val e = assertThrows(BackupException::class.java) { BackupCodec.validate(file) }
        assertThat(e.error).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun duplicateIdsAreDamaged() {
        val s = everythingSnapshot()
        val file = fileOf(s.copy(gaps = s.gaps + s.gaps.first().copy(cause = GapCause.NO_FIX)))
        val e = assertThrows(BackupException::class.java) { BackupCodec.validate(file) }
        assertThat(e.error).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun aSettingThatDoesNotParseIsDamagedAndAnUnknownTypeIsSkipped() {
        val bad = listOf(PrefEntry("n", "int", "abc"))
        assertThat(assertThrows(BackupException::class.java) { bad.toPreferences() }.error).isEqualTo(BackupError.DAMAGED)

        val future = listOf(PrefEntry("x", "quaternion", "1"), PrefEntry("ok", "int", "2"))
        assertThat(future.toPreferences().asMap().keys.map { it.name }).containsExactly("ok")
    }

    @Test
    fun everyEntityPropertyTakesAtLeastTwoDifferentValuesInTheFixture() {
        val s = everythingSnapshot()
        val tables: Map<String, List<Any>> = mapOf(
            "sessions" to s.sessions, "pings" to s.pings, "jobs" to s.jobs, "places" to s.places, "visits" to s.visits,
            "trips" to s.trips, "jobBlocks" to s.jobBlocks, "excludedRanges" to s.excludedRanges, "gaps" to s.gaps,
            "scheduleTemplates" to s.scheduleTemplates, "scheduleOverrides" to s.scheduleOverrides,
            "manualOverrides" to s.manualOverrides, "checkpoints" to s.checkpoints,
        )
        for ((name, rows) in tables) {
            assertThat(rows.size).isAtLeast(2)
            val fields = rows.first().javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            assertThat(fields).isNotEmpty()
            for (field in fields) {
                field.isAccessible = true
                val values = rows.map { row -> field.get(row).let { if (it is ByteArray) it.toList() else it } }.toSet()
                assertWithMessage("$name.${field.name} takes one value only in the fixture").that(values.size).isAtLeast(2)
            }
        }
    }

    @Test
    fun theAccuracySentinelSurvivesTheFile() {
        val decoded = BackupCodec.validate(decode(encodeFile(fileOf(everythingSnapshot()))))
        assertThat(decoded.pings.map { it.accuracyM }).contains(Float.MAX_VALUE)
    }

    @Test
    fun aCompleteGzipOfCutOffJsonThatIsOursIsDamagedNotForeign() {
        val cut = gzip("""{"format":"tarry-backup","formatVersion":1,"tables":{"sessions":[{"id":1,"sta""")
        assertThat(errorOf(cut)).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun aProviderFailureMidReadIsReportedAsCannotOpen() {
        val good = encodeFile(fileOf(everythingSnapshot()))
        val e = assertThrows(BackupException::class.java) {
            decodeAll {
                object : java.io.FilterInputStream(ByteArrayInputStream(good)) {
                    var n = 0
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (n++ > 0) throw IOException("provider died")
                        return super.read(b, off, minOf(len, 64))
                    }
                }
            }
        }
        assertThat(e.error).isEqualTo(BackupError.CANNOT_OPEN)
    }

    private val inlineJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    private fun lines(bytes: ByteArray): List<String> =
        java.util.zip.GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8).trimEnd().lines()

    private fun manyPings(n: Int, sessionId: Long = 1) = (1..n).map { i ->
        PingDto(i.toLong(), 1_000L + i, "UTC", i / 1000.0, -i / 1000.0, 5f, null, null, null, PingSource.FUSED, sessionId)
    }

    /** Collects every chunk the callback is handed. */
    private fun chunksOf(bytes: ByteArray): List<List<PingDto>> = runBlocking {
        val chunks = mutableListOf<List<PingDto>>()
        BackupCodec.read({ ByteArrayInputStream(bytes) }) { chunks += it }
        chunks
    }

    @Test
    fun theFileIsAHeaderLineThenOnePingPerLine() {
        val file = fileOf(everythingSnapshot())
        val l = lines(encodeFile(file))

        assertThat(l).hasSize(1 + 3)
        assertThat(l[0]).contains("\"format\":\"tarry-backup\"")
        assertThat(l[0]).contains("\"pings\":[]")
        assertThat(l.drop(1).map { Regex("\"id\":(\\d+)").find(it)!!.groupValues[1] }).containsExactly("10", "11", "12").inOrder()
    }

    @Test
    fun headerAndPingLinesRoundTripWithEveryFieldNullsAndEnums() {
        val snapshot = everythingSnapshot()
        val bytes = encodeFile(fileOf(snapshot))

        val chunks = chunksOf(bytes)

        assertThat(chunks.flatten().map { it.toEntity() }).isEqualTo(snapshot.pings)
        val ping = chunks.flatten().single { it.id == 11L }
        assertThat(ping.speedMps).isNull()
        assertThat(ping.activity).isNull()
        assertThat(ping.source).isEqualTo(PingSource.MANUAL_START)
    }

    @Test
    fun anEarlierLayoutWithInlinePingsInTheHeaderDecodesThroughTheSameCallback() {
        val snapshot = everythingSnapshot()
        val inline = gzip(inlineJson.encodeToString(BackupFile.serializer(), fileOf(snapshot)))

        val chunks = chunksOf(inline)

        assertThat(chunks.flatten().map { it.toEntity() }).isEqualTo(snapshot.pings)
        val header = decodeAll { ByteArrayInputStream(inline) }
        assertThat(BackupCodec.validate(header)).isEqualTo(snapshot)
    }

    @Test
    fun inlinePingsAndTrailingPingLinesAreBothDelivered() {
        val snapshot = everythingSnapshot()
        val json = inlineJson
        val head = json.encodeToString(BackupFile.serializer(), fileOf(snapshot.copy(pings = snapshot.pings.take(2))))
        val extra = json.encodeToString(PingDto.serializer(), snapshot.pings[2].toDto())

        val got = chunksOf(gzip("$head\n$extra\n")).flatten()

        assertThat(got.map { it.id }).containsExactly(10L, 11L, 12L).inOrder()
    }

    @Test
    fun aGzipStreamCutMidPingsIsDamaged() {
        val s = everythingSnapshot()
        val big = fileOf(s.copy(pings = emptyList())).let { it.copy(tables = it.tables.copy(pings = manyPings(5_000, 1))) }
        val bytes = encodeFile(big)
        assertThat(chunksOf(bytes).flatten()).hasSize(5_000)

        val e = assertThrows(BackupException::class.java) { chunksOf(bytes.copyOf(bytes.size * 2 / 3)) }

        assertThat(e.error).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun aPingLineThatDoesNotDecodeIsDamaged() {
        val head = lines(encodeFile(fileOf(everythingSnapshot().copy(pings = emptyList())))).first()
        val e = assertThrows(BackupException::class.java) { chunksOf(gzip("$head\n{\"id\":1}\n")) }
        assertThat(e.error).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun theCallbackNeverReceivesMoreThanFiveHundredPingsAtOnce() {
        val s = everythingSnapshot()
        val file = fileOf(s).let { it.copy(tables = it.tables.copy(pings = manyPings(1_234, 1))) }

        val chunks = chunksOf(encodeFile(file))

        assertThat(chunks.map { it.size }).containsExactly(500, 500, 234).inOrder()
        assertThat(chunks.flatten().map { it.id }).isEqualTo((1L..1234L).toList())
    }

    @Test
    fun checkPingsRejectsAnUnknownSessionADuplicateIdAcrossChunksAndAcceptsGoodOnes() {
        val ok = manyPings(3, 1)
        val seen = HashSet<Long>()
        BackupCodec.checkPings(ok, setOf(1L), seen)
        assertThat(seen).containsExactly(1L, 2L, 3L)

        val unknown = assertThrows(BackupException::class.java) { BackupCodec.checkPings(manyPings(1, 404), setOf(1L), HashSet()) }
        assertThat(unknown.error).isEqualTo(BackupError.DAMAGED)
        val dup = assertThrows(BackupException::class.java) { BackupCodec.checkPings(listOf(ok[0]), setOf(1L), seen) }
        assertThat(dup.error).isEqualTo(BackupError.DAMAGED)
    }

    @Test
    fun anExceptionFromTheCallbackPassesThroughUnchanged() {
        val bytes = encodeFile(fileOf(everythingSnapshot()))
        val e = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { BackupCodec.read({ ByteArrayInputStream(bytes) }) { throw IllegalArgumentException("mine") } }
        }
        assertThat(e.message).isEqualTo("mine")
    }

    @Test
    fun duplicateSettingKeysAreDamaged() {
        val dup = listOf(PrefEntry("a", "int", "1"), PrefEntry("a", "int", "2"))
        assertThat(assertThrows(BackupException::class.java) { dup.toPreferences() }.error).isEqualTo(BackupError.DAMAGED)
    }
}
