package me.tarry.app.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Whether decorative motion (pulse, shimmer, slides) is allowed. Injectable so tests can flip it. */
fun interface MotionPreferences {
    fun isReducedMotion(): Boolean
}

/** Reads the system animator duration scale; 0 means the user turned animations off. */
class SystemMotionPreferences(private val context: Context) : MotionPreferences {
    override fun isReducedMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** Override with a [MotionPreferences] (e.g. in tests); null means "read the system setting". */
val LocalMotionPreferences = compositionLocalOf<MotionPreferences?> { null }

/** True when decorative motion should be replaced by a static presentation. */
@Composable
fun rememberReducedMotion(): Boolean {
    val override = LocalMotionPreferences.current
    val context = LocalContext.current
    val prefs = override ?: remember(context) { SystemMotionPreferences(context) }
    return prefs.isReducedMotion()
}
