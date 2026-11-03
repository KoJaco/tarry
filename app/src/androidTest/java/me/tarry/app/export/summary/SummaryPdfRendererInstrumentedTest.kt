package me.tarry.app.export.summary

import androidx.test.platform.app.InstrumentationRegistry
import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.SummaryInput
import me.tarry.app.summary.SummaryRange
import me.tarry.app.summary.calculateSummary
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.settings.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Robolectric cannot produce a PDF (its `PdfDocument` has no native backing: "document is closed"), so the renderer is
 * checked on a device: the output is a PDF, and it has as many pages as the layout. Synthetic data only.
 */
class SummaryPdfRendererInstrumentedTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val first = LocalDate.of(2025, 11, 10)
    private val range = SummaryRange(first, first.plusDays(6))

    private fun at(day: Int, h: Int) = ZonedDateTime.of(first.plusDays(day.toLong()), LocalTime.of(h, 0), zone).toInstant().toEpochMilli()

    private val snapshot: SummarySnapshot = run {
        val rules = BillingRules(15, RoundingMode.NEAREST, 30, perDay = true)
        val jobs = listOf(
            Job(id = "A", name = "Alpha", colorArgb = 1, updatedAt = 0),
            Job(id = "B", name = "Bravo", colorArgb = 2, updatedAt = 0, archived = true),
        )
        val blocks = (0 until 5).map { d ->
            JobBlock(id = "a$d", jobId = "A", start = at(d, 9), end = at(d, 12), zoneId = zone.id, billable = true, notes = "Day $d, on site", updatedAt = 0)
        } + JobBlock(id = "b", jobId = "B", start = at(1, 13), end = at(1, 15), zoneId = zone.id, billable = true, updatedAt = 0)
        SummarySnapshot(range, zone, calculateSummary(SummaryInput(range, zone, blocks, jobs, emptyList(), emptyList(), emptySet(), rules)), rules)
    }

    @Test
    fun writesAPdf_withAsManyPagesAsTheLayout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = ByteArrayOutputStream()
        val pages = SummaryPdfRenderer(context).render(snapshot, out)
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF"))
        // Cover plus one page for each of the two jobs.
        assertEquals(3, pages)
        val declared = Regex("""/Count\s+(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
        val pageObjects = Regex("""/Type\s*/Page(?![s\w])""").findAll(text).count()
        assertTrue("declared=$declared objects=$pageObjects", declared == pages || pageObjects == pages)
    }
}
