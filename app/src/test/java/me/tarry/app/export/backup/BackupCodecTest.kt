package me.tarry.app.export.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.SessionEndReason
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class BackupCodecTest {
    private fun decode(bytes: ByteArray) = BackupCodec.decode { ByteArrayInputStream(bytes) }

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
        val e = assertThrows(BackupException::class.java) { BackupCodec.decode { throw IOException("no") } }
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
}
