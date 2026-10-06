package ai.pivotstudio.via.android.core

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single/double/long tap + 4-direction swipe recognizer, built on raw
 * Compose pointer input (not [androidx.compose.foundation.gestures.detectTapGestures],
 * which has no swipe concept and no built-in single-vs-double-tap
 * disambiguation window). This is the ONLY navigation input for a
 * non-sighted user in this app (PLAN.md Phase 4) — it must stay a plain,
 * dependency-free recognizer so its behavior is easy to reason about and
 * tune (thresholds below) without fighting a higher-level gesture API's
 * own disambiguation logic.
 *
 * Deliberately NOT integrated with Android's TalkBack explore-by-touch —
 * this app is designed to be the primary non-sighted UI on its own
 * (TTS readback + confirm everywhere already assumes TalkBack isn't the
 * input channel), not layered under it. See PLAN.md Phase 4 for this
 * scope decision.
 */
sealed class GestureEvent {
    data object SingleTap : GestureEvent()
    data object DoubleTap : GestureEvent()
    data object LongPress : GestureEvent()
    data object SwipeLeft : GestureEvent()
    data object SwipeRight : GestureEvent()
    data object SwipeUp : GestureEvent()
    data object SwipeDown : GestureEvent()
}

/** Tunable thresholds, exposed so they're easy to adjust after on-device feedback. */
object GestureTuning {
    const val DOUBLE_TAP_WINDOW_MS = 300L
    const val LONG_PRESS_MS = 500L
    const val SWIPE_MIN_DISTANCE_DP = 48f
}

/**
 * Attaches the gesture recognizer to this [Modifier]'s element. [onGesture]
 * fires once per recognized gesture — a single tap is only reported after
 * [GestureTuning.DOUBLE_TAP_WINDOW_MS] elapses with no second tap, so a
 * double tap is never also reported as two single taps.
 *
 * One pointer-down/up cycle is classified in this order: long press (held
 * past [GestureTuning.LONG_PRESS_MS] without enough movement to count as a
 * swipe) > swipe (released after moving at least [GestureTuning.SWIPE_MIN_DISTANCE_DP]
 * in one direction) > tap (released quickly with little movement — then
 * raced against a second tap to decide single vs double).
 */
fun Modifier.gestureNavigation(onGesture: (GestureEvent) -> Unit): Modifier = composed {
    val density = LocalDensity.current
    val minSwipePx = with(density) { GestureTuning.SWIPE_MIN_DISTANCE_DP.dp.toPx() }

    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val downPosition = down.position

            // Race "pointer released" against the long-press timer. Exactly
            // one of releasedPosition / timedOut is meaningful afterward.
            var releasedPosition: Offset? = null
            val longPressElapsed = withTimeoutOrNull(GestureTuning.LONG_PRESS_MS) {
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp()) {
                        releasedPosition = change.position
                        break
                    }
                }
            }

            if (longPressElapsed == null) {
                // Timer won the race: still held past the long-press threshold.
                onGesture(GestureEvent.LongPress)
                // Drain until actual release so the next gesture starts clean.
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp()) break
                }
                return@awaitEachGesture
            }

            val upPosition = releasedPosition ?: downPosition
            val dx = upPosition.x - downPosition.x
            val dy = upPosition.y - downPosition.y

            if (abs(dx) >= minSwipePx || abs(dy) >= minSwipePx) {
                val swipe = if (abs(dx) > abs(dy)) {
                    if (dx > 0) GestureEvent.SwipeRight else GestureEvent.SwipeLeft
                } else {
                    if (dy > 0) GestureEvent.SwipeDown else GestureEvent.SwipeUp
                }
                onGesture(swipe)
                return@awaitEachGesture
            }

            // Quick release with little movement -> candidate tap. Wait for
            // a second down within the double-tap window to disambiguate.
            val secondDown = withTimeoutOrNull(GestureTuning.DOUBLE_TAP_WINDOW_MS) {
                awaitFirstDown(pass = PointerEventPass.Initial)
            }
            if (secondDown != null) {
                onGesture(GestureEvent.DoubleTap)
                // Consume the second tap's release so it doesn't leak into
                // the next gesture as a stray single tap.
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == secondDown.id } ?: break
                    if (change.changedToUp()) break
                }
                return@awaitEachGesture
            }
            onGesture(GestureEvent.SingleTap)
        }
    }
}
