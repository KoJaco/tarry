package me.tarry.app.export.summary

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Draws the Summary PDF (phase 7 step 5): the pages [SummaryPdfLayout] works out, put on a `PdfDocument` and written to a
 * stream. No logic of its own. Black on white whatever the app theme, in the system sans font.
 */
@Singleton
class SummaryPdfRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Writes [snapshot] as a PDF to [out] (not closed) and returns the number of pages. */
    fun render(snapshot: SummarySnapshot, out: OutputStream): Int = render(snapshot, AndroidSummaryPdfText(context), out)

    internal fun render(snapshot: SummarySnapshot, text: SummaryPdfText, out: OutputStream): Int {
        val paints = PdfTextStyle.entries.associateWith { style ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
                textSize = style.size
                color = Color.BLACK
            }
        }
        val measurer = object : TextMeasurer {
            override fun width(text: String, style: PdfTextStyle) = paints.getValue(style).measureText(text)
        }
        val pages = SummaryPdfLayout.layout(snapshot, text, measurer)
        val rule = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
        }
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(PdfPageSpec.WIDTH.toInt(), PdfPageSpec.HEIGHT.toInt(), index + 1).create()
                val pdfPage = document.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(Color.WHITE)
                for (r in page.rules) {
                    rule.strokeWidth = r.thickness
                    canvas.drawLine(r.x1, r.y, r.x2, r.y, rule)
                }
                for (run in page.runs) canvas.drawText(run.text, run.x, run.baseline, paints.getValue(run.style))
                document.finishPage(pdfPage)
            }
            document.writeTo(out)
            out.flush()
        } finally {
            document.close()
        }
        return pages.size
    }
}
