package me.tarry.app.export.summary

import com.google.common.truth.Truth.assertThat
import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.SummaryInput
import me.tarry.app.summary.SummaryRange
import me.tarry.app.summary.SummaryResult
import me.tarry.app.summary.calculateSummary
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.settings.RoundingMode
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Golden tests on synthetic data only. */
class SummaryCsvTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val min = 60_000L

    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, mi, 0, 0, sydney).toInstant().toEpochMilli()

    private fun nov(d: Int, h: Int, mi: Int = 0) = at(2025, 11, d, h, mi)

    private val jobA = Job(id = "job-a", name = "Alpha", colorArgb = 1, updatedAt = 0)
    private val jobB = Job(id = "job-b", name = "Bravo", colorArgb = 2, updatedAt = 0)

    private fun block(
        id: String, job: String, start: Long, end: Long, billable: Boolean = true, notes: String? = null,
    ) = JobBlock(id = id, jobId = job, start = start, end = end, zoneId = sydney.id, billable = billable, notes = notes, updatedAt = 0)

    private fun calc(
        blocks: List<JobBlock>,
        rules: BillingRules = BillingRules(),
        from: LocalDate = LocalDate.of(2025, 11, 10),
        to: LocalDate = from,
        jobs: List<Job> = listOf(jobA, jobB),
    ): SummaryResult = calculateSummary(SummaryInput(SummaryRange(from, to), sydney, blocks, jobs, emptyList(), emptyList(), emptySet(), rules))

    private fun csv(result: SummaryResult): String = StringBuilder().also { SummaryCsv.write(result, sydney, it) }.toString()

    private fun rows(result: SummaryResult): List<String> = SummaryCsv.rows(result, sydney)

    /** Plain split: only for rows whose fields hold no commas, quotes or line breaks. */
    private fun fields(row: String): List<String> = row.split(",")

    private fun billedSum(rows: List<String>, type: String) =
        rows.map(::fields).filter { it[0] == type }.sumOf { it[9].ifEmpty { "0" }.toLong() }

    @Test
    fun header_isFrozen() {
        assertThat(SummaryCsv.HEADER)
            .isEqualTo("row_type,job_block_id,job_id,job_name,date,start,end,duration_min,billable,billed_min,clipped,notes")
        assertThat(csv(SummaryResult.Empty)).isEqualTo(SummaryCsv.HEADER + "\r\n")
    }

    @Test
    fun oneBlock_goldenLine_andCrlfEnds_noBom() {
        val result = calc(listOf(block("b1", "job-a", nov(10, 9), nov(10, 11, 30), notes = "Rewired")))
        assertThat(csv(result)).isEqualTo(
            SummaryCsv.HEADER + "\r\n" +
                "block,b1,job-a,Alpha,2025-11-10,2025-11-10T09:00:00+11:00,2025-11-10T11:30:00+11:00,150,true,150,false,Rewired\r\n",
        )
        assertThat(csv(result).startsWith("﻿")).isFalse()
    }

    @Test
    fun escaping_commasQuotesCrAndLf_inNotesAndJobNames() {
        val tricky = Job(id = "job-t", name = "Smith, \"Jones\" & Co", colorArgb = 1, updatedAt = 0)
        val result = calc(
            listOf(
                block("b1", "job-t", nov(10, 9), nov(10, 10), notes = "a,b"),
                block("b2", "job-t", nov(10, 10), nov(10, 11), notes = "say \"hi\""),
                block("b3", "job-t", nov(10, 11), nov(10, 12), notes = "line1\r\nline2"),
                block("b4", "job-t", nov(10, 12), nov(10, 13), notes = "cr\rlf\n"),
            ),
            jobs = listOf(tricky),
        )
        val name = "\"Smith, \"\"Jones\"\" & Co\""
        val r = rows(result)
        assertThat(r[0]).isEqualTo("block,b1,job-t,$name,2025-11-10,2025-11-10T09:00:00+11:00,2025-11-10T10:00:00+11:00,60,true,60,false,\"a,b\"")
        assertThat(r[1]).endsWith(",false,\"say \"\"hi\"\"\"")
        assertThat(r[2]).endsWith(",false,\"line1\r\nline2\"")
        assertThat(r[3]).endsWith(",false,\"cr\rlf\n\"")
    }

    @Test
    fun formulaGuard_prefixesAnApostropheOnNotesStartingWithAFormulaCharacter() {
        val result = calc(
            listOf(
                block("b1", "job-a", nov(10, 9), nov(10, 10), notes = "=SUM(A1)"),
                block("b2", "job-a", nov(10, 10), nov(10, 11), notes = "+1"),
                block("b3", "job-a", nov(10, 11), nov(10, 12), notes = "-2"),
                block("b4", "job-a", nov(10, 12), nov(10, 13), notes = "@home"),
                block("b5", "job-a", nov(10, 13), nov(10, 14), notes = " =x"),
                block("b6", "job-a", nov(10, 14), nov(10, 15), notes = "=a,b"),
                block("b7", "job-a", nov(10, 15), nov(10, 16), notes = "a=b"),
            ),
        )
        val r = rows(result)
        assertThat(r[0]).endsWith(",false,'=SUM(A1)")
        assertThat(r[1]).endsWith(",false,'+1")
        assertThat(r[2]).endsWith(",false,'-2")
        assertThat(r[3]).endsWith(",false,'@home")
        assertThat(r[4]).endsWith(",false, =x")
        assertThat(r[5]).endsWith(",false,\"'=a,b\"")
        assertThat(r[6]).endsWith(",false,a=b")
    }

    @Test
    fun formulaGuard_leavesJobNamesAlone() {
        val job = Job(id = "job-f", name = "=Job", colorArgb = 1, updatedAt = 0)
        val r = rows(calc(listOf(block("b1", "job-f", nov(10, 9), nov(10, 10))), jobs = listOf(job)))
        assertThat(r[0]).startsWith("block,b1,job-f,=Job,")
    }

    @Test
    fun offsets_onTheSydneyDstEndDay() {
        // 2026-04-05: clocks go back at 03:00 AEDT (+11:00) to 02:00 AEST (+10:00). 01:00 to 04:00 is four real hours.
        val d = LocalDate.of(2026, 4, 5)
        val result = calc(listOf(block("b1", "job-a", at(2026, 4, 5, 1), at(2026, 4, 5, 4))), from = d)
        assertThat(rows(result).single())
            .isEqualTo("block,b1,job-a,Alpha,2026-04-05,2026-04-05T01:00:00+11:00,2026-04-05T04:00:00+10:00,240,true,240,false,")
    }

    @Test
    fun offsets_onTheSydneyDstStartDay() {
        // 2026-10-04: clocks go forward at 02:00 AEST (+10:00) to 03:00 AEDT (+11:00). 01:00 to 04:00 is two real hours.
        val d = LocalDate.of(2026, 10, 4)
        val result = calc(listOf(block("b1", "job-a", at(2026, 10, 4, 1), at(2026, 10, 4, 4))), from = d)
        assertThat(rows(result).single())
            .isEqualTo("block,b1,job-a,Alpha,2026-10-04,2026-10-04T01:00:00+10:00,2026-10-04T04:00:00+11:00,120,true,120,false,")
    }

    @Test
    fun clipping_atBothRangeEdges_isMarked() {
        // Range 10th..11th. A starts the 9th (cut at the start), B ends the 12th (cut at the end), C is whole.
        val result = calc(
            listOf(
                block("a", "job-a", nov(9, 22), nov(10, 2)),
                block("b", "job-a", nov(11, 22), nov(12, 3)),
                block("c", "job-a", nov(10, 12), nov(10, 13)),
            ),
            from = LocalDate.of(2025, 11, 10), to = LocalDate.of(2025, 11, 11),
        )
        val byBlock = rows(result).map(::fields).associateBy { it[1] }
        assertThat(byBlock.getValue("a")[10]).isEqualTo("true")
        assertThat(byBlock.getValue("a")[5]).isEqualTo("2025-11-10T00:00:00+11:00")
        assertThat(byBlock.getValue("a")[7]).isEqualTo("120")
        assertThat(byBlock.getValue("b")[10]).isEqualTo("true")
        assertThat(byBlock.getValue("b")[6]).isEqualTo("2025-11-12T00:00:00+11:00")
        assertThat(byBlock.getValue("b")[7]).isEqualTo("120")
        assertThat(byBlock.getValue("c")[10]).isEqualTo("false")
    }

    @Test
    fun midnightSplit_isNotClipped_andRepeatsTheBlockId() {
        val result = calc(listOf(block("n", "job-a", nov(10, 22), nov(11, 2))), to = LocalDate.of(2025, 11, 11))
        val r = rows(result).map(::fields)
        assertThat(r).hasSize(2)
        assertThat(r.map { it[1] }).containsExactly("n", "n")
        assertThat(r.map { it[4] }).containsExactly("2025-11-10", "2025-11-11").inOrder()
        assertThat(r.map { it[10] }).containsExactly("false", "false")
        assertThat(r.map { it[7] }).containsExactly("120", "120")
        assertThat(r[0][6]).isEqualTo("2025-11-11T00:00:00+11:00")
        assertThat(r[1][5]).isEqualTo("2025-11-11T00:00:00+11:00")
    }

    @Test
    fun perBlockMode_billedMinSumsToBilled() {
        val rules = BillingRules(incrementMin = 15, mode = RoundingMode.UP, minimumMin = 30)
        val result = calc(
            listOf(
                block("a", "job-a", nov(10, 9), nov(10, 9, 10)),
                block("b", "job-a", nov(10, 10), nov(10, 10, 40)),
                block("c", "job-b", nov(10, 9), nov(10, 11, 7)),
                block("d", "job-b", nov(10, 12), nov(10, 13), billable = false),
            ),
            rules,
        )
        val r = rows(result)
        assertThat(r.map(::fields).none { it[0] == "day_total" }).isTrue()
        assertThat(billedSum(r, "block") * min).isEqualTo(result.billedMs)
        assertThat(billedSum(r, "block")).isEqualTo(30 + 45 + 135)
    }

    @Test
    fun perDayMode_blockRowsHaveNoBilledMin_andDayTotalsSumToBilled() {
        val rules = BillingRules(incrementMin = 15, mode = RoundingMode.NEAREST, minimumMin = 0, perDay = true)
        val result = calc(
            listOf(
                block("a", "job-a", nov(10, 9), nov(10, 9, 40)),
                block("b", "job-a", nov(10, 11), nov(10, 11, 40)),
                block("c", "job-a", nov(11, 9), nov(11, 10)),
            ),
            rules, to = LocalDate.of(2025, 11, 11),
        )
        val r = rows(result).map(::fields)
        assertThat(r.filter { it[0] == "block" }.map { it[9] }).containsExactly("", "", "")
        val totals = r.filter { it[0] == "day_total" }
        assertThat(totals).hasSize(2)
        assertThat(totals[0]).containsExactly("day_total", "", "job-a", "Alpha", "2025-11-10", "", "", "80", "", "75", "", "").inOrder()
        assertThat(totals.sumOf { it[9].toLong() } * min).isEqualTo(result.billedMs)
    }

    @Test
    fun nonBillable_billsZeroPerBlock_andIsLeftOutOfDayTotal() {
        val perBlock = calc(listOf(block("n", "job-a", nov(10, 9), nov(10, 10), billable = false)))
        val row = rows(perBlock).single().let(::fields)
        assertThat(row[8]).isEqualTo("false")
        assertThat(row[9]).isEqualTo("0")
        assertThat(row[7]).isEqualTo("60")

        val perDay = calc(
            listOf(
                block("n", "job-a", nov(10, 9), nov(10, 10), billable = false),
                block("m", "job-b", nov(10, 9), nov(10, 10)),
            ),
            BillingRules(perDay = true),
        )
        val r = rows(perDay).map(::fields)
        // job-a has only a non-billable block that day: no day_total row for it. job-b has one.
        assertThat(r.filter { it[0] == "day_total" }.map { it[2] }).containsExactly("job-b")
        assertThat(r.single { it[1] == "n" }[9]).isEqualTo("")
    }

    @Test
    fun order_isDateThenStartThenJobName_andDayTotalFollowsTheJobsLastRow() {
        val result = calc(
            listOf(
                block("late", "job-a", nov(11, 9), nov(11, 10)),
                block("b2", "job-b", nov(10, 9), nov(10, 10)),
                block("a2", "job-a", nov(10, 13), nov(10, 14)),
                block("a1", "job-a", nov(10, 9), nov(10, 10)),
                block("b1", "job-b", nov(10, 11), nov(10, 12)),
            ),
            BillingRules(perDay = true), to = LocalDate.of(2025, 11, 11),
        )
        val r = rows(result).map(::fields).map { it[0] + ":" + (it[1].ifEmpty { it[3] }) }
        assertThat(r).containsExactly(
            "block:a1", // 09:00 Alpha before 09:00 Bravo
            "block:b2",
            "block:b1",
            "day_total:Bravo", // Bravo's last row of the 10th
            "block:a2",
            "day_total:Alpha", // Alpha's last row of the 10th, after Bravo's rows that sit between
            "block:late",
            "day_total:Alpha",
        ).inOrder()
    }

    @Test
    fun output_isLocaleIndependent() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            val result = calc(listOf(block("b1", "job-a", nov(10, 9), nov(10, 10))))
            assertThat(rows(result).single())
                .isEqualTo("block,b1,job-a,Alpha,2025-11-10,2025-11-10T09:00:00+11:00,2025-11-10T10:00:00+11:00,60,true,60,false,")
        } finally {
            java.util.Locale.setDefault(old)
        }
    }
}
