package me.tarry.app.export.backup

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Opt-in generator of the marketing demo backup. Skipped unless the system property `tarry.demo` is set to `today` (in
 * Australia/Melbourne) or an ISO date: `gradlew.bat :app:testDebugUnitTest --tests *DemoBackupGeneratorTest -Ptarry.demo=today`.
 * Writes `app/build/demo/tarry-demo.json` (gzip, as the app writes backups). Restore it in Settings > Data on an emulator.
 */
class DemoBackupGeneratorTest {
    @Test
    fun writeDemoBackup() {
        val arg = System.getProperty("tarry.demo")
        assumeTrue("tarry.demo not set", !arg.isNullOrBlank())
        val day = if (arg == "today") LocalDate.now(ZoneId.of(DemoBackup.ZONE)) else LocalDate.parse(arg)
        val out = File(DemoBackup.OUTPUT_PATH).absoluteFile
        out.parentFile?.mkdirs()
        out.outputStream().use { BackupCodec.encode(DemoBackup.build(day), it) }
        println("Demo backup for $day written to $out (${out.length()} bytes)")
    }
}
