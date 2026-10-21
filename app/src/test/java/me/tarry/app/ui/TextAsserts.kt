package me.tarry.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat

/** The text layout of the first Text in this node (merged descendants included). */
internal fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    assertThat(results).isNotEmpty()
    return results.first()
}

/**
 * Asserts the text is not truncated. hasVisualOverflow is unreliable under Robolectric (line-height trimming), so this
 * checks what truncation means: no lines dropped, none ellipsized, and the text fits the width it was given.
 */
internal fun SemanticsNodeInteraction.assertNotTruncated(): SemanticsNodeInteraction {
    val r = textLayout()
    assertThat(r.multiParagraph.didExceedMaxLines).isFalse()
    assertThat((0 until r.lineCount).any { r.isLineEllipsized(it) }).isFalse()
    assertThat(r.size.width).isAtMost(r.layoutInput.constraints.maxWidth)
    // A non-wrapping text that overflows is clipped: its lines then run past the laid-out width.
    for (i in 0 until r.lineCount) assertThat(r.getLineRight(i)).isAtMost(r.size.width + 0.5f)
    return this
}
