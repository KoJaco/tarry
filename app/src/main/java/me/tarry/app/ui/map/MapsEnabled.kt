package me.tarry.app.ui.map

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether [TarryMap] may create a real MapView. Compose tests under Robolectric provide false because MapLibre's
 * native library cannot load there; [TarryMap] then draws an inset placeholder box.
 */
val LocalMapsEnabled = staticCompositionLocalOf { true }
