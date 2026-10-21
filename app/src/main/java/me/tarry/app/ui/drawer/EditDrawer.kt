package me.tarry.app.ui.drawer

import androidx.compose.runtime.Composable

/**
 * The edit drawer for [target]. Hosted by the timeline screen, which keeps the open target; [onRetarget] swaps it
 * (e.g. a trip's "Add a stop" opens [EditTarget.NewVisit], and saving a new visit reopens it as [EditTarget.Visit]).
 *
 * Contract stub: T2 replaces the body; the signature is what the timeline (T3) builds against.
 */
@Composable
fun EditDrawer(
    target: EditTarget,
    onDismiss: () -> Unit,
    onRetarget: (EditTarget) -> Unit,
) {
}
