package me.tarry.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** One theme's colours for a job block: tinted container, 1.5 dp border and dark text. */
@Immutable
data class JobColors(val container: Color, val border: Color, val text: Color)

/** A job colour slot. [key] is the light container ARGB, which is what `Job.colorArgb` stores. */
@Immutable
data class JobSlot(val name: String, val light: JobColors, val dark: JobColors) {
    val key: Int get() = light.container.toArgb()

    fun colors(dark: Boolean): JobColors = if (dark) this.dark else light
}

private fun slot(
    name: String,
    lc: Long, lb: Long, lt: Long,
    dc: Long, db: Long, dt: Long,
) = JobSlot(
    name,
    JobColors(Color(0xFF000000 or lc), Color(0xFF000000 or lb), Color(0xFF000000 or lt)),
    JobColors(Color(0xFF000000 or dc), Color(0xFF000000 or db), Color(0xFF000000 or dt)),
)

/** The eight job colour slots from tokens.md. */
object JobPalette {
    val slots: List<JobSlot> = listOf(
        slot("Apricot", 0xFDEBD8, 0xEBA35C, 0x7A3F0B, 0x3A2614, 0xD9894A, 0xF6CDA6),
        slot("Sky", 0xE3EDFB, 0x7FA6E3, 0x1E4A8F, 0x172A45, 0x5F8BD6, 0xB9D0F5),
        slot("Sage", 0xE2F2E6, 0x73BB8C, 0x1F5E36, 0x16301F, 0x5BA676, 0xB4E0C2),
        slot("Lilac", 0xEEE6FA, 0xA98DE0, 0x4E3191, 0x2A1F45, 0x9077D1, 0xD4C6F5),
        slot("Rose", 0xFBE4EA, 0xE28BA2, 0x8A2342, 0x401A25, 0xCF7690, 0xF5C3D1),
        slot("Teal", 0xDDF2F1, 0x5DB5B0, 0x165955, 0x123332, 0x4FA19C, 0xAEE0DD),
        slot("Sand", 0xF3EBDD, 0xC9AE7E, 0x5E4718, 0x33291A, 0xB3996B, 0xE6D6B5),
        slot("Slate", 0xE8EAEE, 0x9AA3B2, 0x353D4A, 0x22262D, 0x848D9C, 0xCDD3DD),
    )

    /** The slot whose light container ARGB equals [colorArgb], or null. */
    fun byColorArgb(colorArgb: Int?): JobSlot? =
        if (colorArgb == null) null else slots.firstOrNull { it.key == colorArgb }

    /** Deterministic fallback slot for a job id (String.hashCode is specified, so stable across runs). */
    fun fallbackFor(jobId: String): JobSlot = slots[Math.floorMod(jobId.hashCode(), slots.size)]

    /** The stored colour's slot if it names one, else the deterministic slot for [jobId]. */
    fun lookup(colorArgb: Int?, jobId: String): JobSlot = byColorArgb(colorArgb) ?: fallbackFor(jobId)
}
