package me.tarry.app.map

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/** Epoch ms of each whole local hour inside `[start, end]` in [zone] (so half-hour zones and DST days stay on the clock). */
fun hourMarks(start: Long, end: Long, zone: ZoneId): List<Long> {
    if (end < start) return emptyList()
    val marks = mutableListOf<Long>()
    var at = Instant.ofEpochMilli(start).atZone(zone).withMinute(0).withSecond(0).withNano(0)
    while (at.toInstant().toEpochMilli() <= end) {
        val ms = at.toInstant().toEpochMilli()
        if (ms >= start) marks += ms
        at = at.plusHours(1)
    }
    return marks
}

/** "9", "12", "1" (12-hour) or "09", "13" (24-hour) for the local hour of [epochMs], with [locale]'s digits. */
fun hourLabel(epochMs: Long, zone: ZoneId, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern(if (is24Hour) "HH" else "h", locale)
        .withDecimalStyle(DecimalStyle.of(locale))
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** 1 to label every hour; 2 to label every second one when labels would sit closer than [MinLabelGapDp] or text is large. */
fun hourLabelStride(hourSpacingDp: Float, fontScale: Float): Int =
    if (hourSpacingDp < MinLabelGapDp || fontScale >= LargeFontScale) 2 else 1

/** The marks that get a label for [stride]: every [stride]th, starting with the first. */
fun <T> thinned(marks: List<T>, stride: Int): List<T> = marks.filterIndexed { i, _ -> i % stride == 0 }

/** A block's left edge and width on the strip, never narrower than [minWidth] so a very short stop still shows. */
fun blockExtent(startX: Float, endX: Float, minWidth: Float): Pair<Float, Float> = startX to maxOf(endX - startX, minWidth)

const val MinLabelGapDp = 24f
const val LargeFontScale = 1.5f
