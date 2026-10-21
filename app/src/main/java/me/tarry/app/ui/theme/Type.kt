package me.tarry.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.tarry.app.R

/** One weight of a bundled variable font (minSdk 29 supports variation settings). */
private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Rounded sans for headings only (600, 700). */
val Nunito = FontFamily(
    variable(R.font.nunito, FontWeight.SemiBold),
    variable(R.font.nunito, FontWeight.Bold),
)

/** Body, dense UI, buttons, captions and the tab bar (400, 600, 700). */
val NunitoSans = FontFamily(
    variable(R.font.nunito_sans, FontWeight.Normal),
    variable(R.font.nunito_sans, FontWeight.SemiBold),
    variable(R.font.nunito_sans, FontWeight.Bold),
)

/**
 * The M3 type scale from tokens.md. Nunito styles (display/headline/titleLarge/titleMedium) are for headings,
 * dates and totals. `labelSmall` is a section header: the spec says upper case, but casing is applied at call
 * sites (`text.uppercase()`), not in the style.
 */
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = Nunito, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Nunito, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Nunito, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Nunito, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Nunito, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = NunitoSans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = NunitoSans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = NunitoSans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = NunitoSans, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = NunitoSans, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp,
    ),
)

/** OpenType tabular figures; applied where numbers must align (a no-op if the font lacks `tnum`). */
const val TabularFeature = "tnum"

/** A style that only turns on tabular numbers; merge into any text style with `+` or [tabular]. */
val TabularNumbers = TextStyle(fontFeatureSettings = TabularFeature)

/** This style with tabular figures (`tnum`). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TabularFeature)
