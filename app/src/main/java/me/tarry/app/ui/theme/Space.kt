package me.tarry.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale from tokens.md (4, 8, 12, 16, 20, 24, 32, 48 dp), with the dp value in each field's name
 * comment. The screen [gutter] is 20 dp, the gap between cards is [s] (8 dp) and between sections is
 * [xl] (20 dp).
 */
@Immutable
data class TarrySpace(
    /** 4 dp */ val xs: Dp = 4.dp,
    /** 8 dp */ val s: Dp = 8.dp,
    /** 12 dp */ val m: Dp = 12.dp,
    /** 16 dp */ val l: Dp = 16.dp,
    /** 20 dp */ val xl: Dp = 20.dp,
    /** 24 dp */ val xxl: Dp = 24.dp,
    /** 32 dp */ val xxxl: Dp = 32.dp,
    /** 48 dp */ val huge: Dp = 48.dp,
    /** Screen gutter, 20 dp. */ val gutter: Dp = 20.dp,
)

/** Shapes from tokens.md: pill (fully rounded), card 20, container 24, block 12, small 8. */
@Immutable
data class TarryShapes(
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
    val card: RoundedCornerShape = RoundedCornerShape(20.dp),
    val container: RoundedCornerShape = RoundedCornerShape(24.dp),
    val block: RoundedCornerShape = RoundedCornerShape(12.dp),
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
)

val LocalTarrySpace = staticCompositionLocalOf { TarrySpace() }
val LocalTarryShapes = staticCompositionLocalOf { TarryShapes() }
