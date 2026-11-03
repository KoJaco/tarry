package me.tarry.app.export.summary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.SummaryInput
import me.tarry.app.summary.SummaryRange
import me.tarry.app.summary.calculateSummary
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SummaryExporterTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2025, 11, 10)
    private val exporter = SummaryExporter(ApplicationProvider.getApplicationContext<Context>(), SummaryPdfRenderer(ApplicationProvider.getApplicationContext<Context>()))

    private fun at(h: Int) = ZonedDateTime.of(day, java.time.LocalTime.of(h, 0), zone).toInstant().toEpochMilli()

    private val snapshot = SummarySnapshot(
        SummaryRange(day, day), zone,
        calculateSummary(
            SummaryInput(
                SummaryRange(day, day), zone,
                listOf(
                    JobBlock(id = "b1", jobId = "A", start = at(9), end = at(10), zoneId = zone.id, billable = true, updatedAt = 0),
                    JobBlock(id = "b2", jobId = "A", start = at(11), end = at(12), zoneId = zone.id, billable = true, updatedAt = 0),
                ),
                listOf(Job(id = "A", name = "Alpha", colorArgb = 1, updatedAt = 0)),
                emptyList(), emptyList(), emptySet(), BillingRules(),
            ),
        ),
    )

    @Test
    fun writesTheCsv_andReturnsTheBlockCount() {
        val bytes = ByteArrayOutputStream()
        val count = runBlocking { exporter.export(SummaryExportFormat.CSV, snapshot, open = { bytes }) }
        assertThat(count).isEqualTo(2)
        val text = bytes.toString(Charsets.UTF_8)
        assertThat(text).startsWith(SummaryCsv.HEADER + "\r\n")
        assertThat(text.split("\r\n").filter { it.startsWith("block,") }).hasSize(2)
    }

    @Test
    fun aWriteFailure_deletesThePartialFile_andIsTyped() {
        var discarded = 0
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        val e = assertThrows(SummaryExportException::class.java) {
            runBlocking { exporter.export(SummaryExportFormat.CSV, snapshot, open = { failing }, discard = { discarded++ }) }
        }
        assertThat(e.error).isEqualTo(SummaryExportError.WRITE_FAILED)
        assertThat(discarded).isEqualTo(1)
    }

    @Test
    fun aDocumentThatCannotBeOpened_isTyped_andTheEmptyDocumentIsDeleted_ignoringDeleteFailures() {
        var discarded = 0
        val e = assertThrows(SummaryExportException::class.java) {
            runBlocking {
                exporter.export(SummaryExportFormat.CSV, snapshot, open = { throw IOException("gone") }, discard = { discarded++ })
            }
        }
        assertThat(e.error).isEqualTo(SummaryExportError.CANNOT_OPEN)
        assertThat(discarded).isEqualTo(1)
        // A delete that throws must not hide the typed error.
        val again = assertThrows(SummaryExportException::class.java) {
            runBlocking {
                exporter.export(
                    SummaryExportFormat.CSV, snapshot, open = { throw SecurityException("revoked") },
                    discard = { throw IOException("cannot delete") },
                )
            }
        }
        assertThat(again.error).isEqualTo(SummaryExportError.CANNOT_OPEN)
    }

    @Test
    fun aPdfFailure_deletesThePartialFile_andIsTyped() {
        // Robolectric's PdfDocument has no native backing, so rendering fails: the same path as a real failure.
        var discarded = 0
        val e = assertThrows(SummaryExportException::class.java) {
            runBlocking { exporter.export(SummaryExportFormat.PDF, snapshot, open = { ByteArrayOutputStream() }, discard = { discarded++ }) }
        }
        assertThat(e.error).isEqualTo(SummaryExportError.WRITE_FAILED)
        assertThat(discarded).isEqualTo(1)
    }

    @Test
    fun aCancellation_deletesThePartialFile_andIsRethrownAsIs() {
        var discarded = 0
        val cancelling = object : OutputStream() {
            override fun write(b: Int) = throw kotlinx.coroutines.CancellationException("cancelled")
            override fun write(b: ByteArray, off: Int, len: Int) = throw kotlinx.coroutines.CancellationException("cancelled")
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { exporter.export(SummaryExportFormat.CSV, snapshot, open = { cancelling }, discard = { discarded++ }) }
        }
        assertThat(discarded).isEqualTo(1)
    }

    @Test
    fun anError_deletesThePartialFile_andIsRethrownAsIs() {
        var discarded = 0
        val oom = object : OutputStream() {
            override fun write(b: Int) = throw OutOfMemoryError("big")
            override fun write(b: ByteArray, off: Int, len: Int) = throw OutOfMemoryError("big")
        }
        assertThrows(OutOfMemoryError::class.java) {
            runBlocking { exporter.export(SummaryExportFormat.CSV, snapshot, open = { oom }, discard = { discarded++ }) }
        }
        assertThat(discarded).isEqualTo(1)
    }

    @Test
    fun suggestedFileName_hasBothIsoDates() {
        assertThat(SummaryExportFormat.CSV.suggestedFileName(SummaryRange(day, day)))
            .isEqualTo("tarry-summary-2025-11-10_2025-11-10.csv")
        assertThat(SummaryExportFormat.CSV.suggestedFileName(SummaryRange(day, day.plusDays(6))))
            .isEqualTo("tarry-summary-2025-11-10_2025-11-16.csv")
        assertThat(SummaryExportFormat.PDF.suggestedFileName(SummaryRange(day, day.plusDays(6))))
            .isEqualTo("tarry-summary-2025-11-10_2025-11-16.pdf")
        assertThat(SummaryExportFormat.PDF.mimeType).isEqualTo("application/pdf")
    }
}
