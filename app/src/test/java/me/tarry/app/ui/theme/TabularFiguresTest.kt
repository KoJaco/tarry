package me.tarry.app.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * tokens.md wants totals and times to align. The bundled variable fonts have no `tnum` feature, so this guards
 * the alternative: their default digits must already all be the same width in every weight the type scale uses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TabularFiguresTest {
    // Unit tests run with the module directory (app/) as the working directory.
    private fun typeface(file: String, weight: Int): Typeface =
        Typeface.Builder(File("src/main/res/font/$file"))
            .setFontVariationSettings("'wght' $weight")
            .build()

    private fun widths(typeface: Typeface, text: String): List<Float> {
        val paint = Paint().apply {
            this.typeface = typeface
            textSize = 100f
        }
        return text.map { paint.measureText(it.toString()) }
    }

    @Test
    fun digitsAreTabularByDefault() {
        // headlineLarge (Nunito 700), plus the weights of the body styles used for times and totals (Nunito Sans 400/600).
        val styles = mapOf(
            "Nunito 700 (headlineLarge)" to typeface("nunito.ttf", 700),
            "Nunito 600" to typeface("nunito.ttf", 600),
            "Nunito Sans 400 (bodyMedium, bodySmall)" to typeface("nunito_sans.ttf", 400),
            "Nunito Sans 600" to typeface("nunito_sans.ttf", 600),
        )
        styles.forEach { (name, face) ->
            val digits = widths(face, "0123456789")
            assertThat(digits.all { it > 0f }).isTrue()
            assertWithMessage(name, digits).that(digits.max() - digits.min()).isLessThan(0.01f)
        }
    }

    @Test
    fun measurementSeesProportionalGlyphs() {
        // Proves the test is measuring the real font: letters are proportional, so the digit check is not vacuous.
        val w = widths(typeface("nunito_sans.ttf", 400), "im")
        assertThat(w[1]).isGreaterThan(w[0] * 1.5f)
    }

    private fun assertWithMessage(name: String, digits: List<Float>) =
        com.google.common.truth.Truth.assertWithMessage("$name digit widths $digits")
}
