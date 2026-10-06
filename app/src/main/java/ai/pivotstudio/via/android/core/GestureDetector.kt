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
    /**
     * Hold-to-record threshold (PLAN.md Phase 4, "Andika ujumbe" merged
     * record+confirm region — explicit user request: "Increase the
     * place where you can press and hold the button and release...
     * Same area can also be used for... confirm/cancel"). Deliberately
     * shorter than [LONG_PRESS_MS] so recording starts promptly once
     * the user commits to holding, without being so short that an
     * ordinary tap-in-progress (before release) ever accidentally
     * starts the mic.
     */
    const val HOLD_TO_RECORD_MS = 350L
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
 *
 * [holdToRecord] (PLAN.md Phase 4, "Andika ujumbe"): when true, a long
 * hold is reported via [onHoldStart]/[onHoldEnd] instead of
 * [GestureEvent.LongPress] — [onHoldStart] fires once
 * [GestureTuning.HOLD_TO_RECORD_MS] elapses with the finger still down
 * and roughly stationary, [onHoldEnd] fires on release. This lets ONE
 * region be both the press-and-hold-to-speak/release-to-transcribe
 * record button AND the normal single/double-tap/swipe confirm area —
 * the two were previously split across separate stacked regions,
 * cramping the usable hold area; merging them means "hold anywhere on
 * this card to record, tap/double-tap/swipe anywhere on it to
 * confirm/cancel". Default false preserves the original
 * [GestureEvent.LongPress]-based behavior for every other screen in
 * the app (main-menu long-press for the sub-menu announcement, etc.) —
 * this is purely additive, no existing call site's behavior changes.
 */
fun Modifier.gestureNavigation(
    holdToRecord: Boolean = false,
    onHoldStart: () -> Unit = {},
    onHoldEnd: () -> Unit = {},
    onGesture: (GestureEvent) -> Unit,
): Modifier = composed {
    val density = LocalDensity.current
    val minSwipePx = with(density) { GestureTuning.SWIPE_MIN_DISTANCE_DP.dp.toPx() }

    pointerInput(holdToRecord) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Main)
            val downPosition = down.position
            val downTimeMs = System.currentTimeMillis()

            // Long-press detection WITHOUT ever cancelling a suspended
            // awaitPointerEvent() call (no withTimeoutOrNull wrapping the
            // event-await loop here) — cancelling a coroutine mid-suspend
            // inside Compose's low-level pointer-input event dispatch is a
            // known-fragile pattern that can corrupt the pointer-input
            // node's internal state for the NEXT gesture cycle on the same
            // surface. Likely root cause of a real "first gesture works,
            // every gesture after it on the same surface silently does
            // nothing" bug. Instead: poll elapsed time against each real
            // pointer event (Android delivers move events for a held-but-
            // stationary finger at the touch sampling rate, so this still
            // detects a long hold reliably) and only ever await events
            // that actually arrive, uninterrupted.
            var releasedPosition: Offset? = null
            var longPressFired = false
            var holdStarted = false
            val holdThresholdMs = if (holdToRecord) GestureTuning.HOLD_TO_RECORD_MS else GestureTuning.LONG_PRESS_MS
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUp()) {
                    releasedPosition = change.position
                    break
                }
                if (!longPressFired && !holdStarted && System.currentTimeMillis() - downTimeMs >= holdThresholdMs) {
                    val dxSoFar = change.position.x - downPosition.x
                    val dySoFar = change.position.y - downPosition.y
                    if (abs(dxSoFar) < minSwipePx && abs(dySoFar) < minSwipePx) {
                        if (holdToRecord) {
                            holdStarted = true
                            onHoldStart()
                        } else {
                            longPressFired = true
                            onGesture(GestureEvent.LongPress)
                        }
                    }
                }
            }

            if (holdStarted) {
                // Recording was already started via onHoldStart() above —
                // just report the release and end this gesture cycle
                // cleanly, skipping tap/swipe classification entirely
                // (a hold-then-release is never also a tap or swipe).
                onHoldEnd()
                return@awaitEachGesture
            }

            if (longPressFired) {
                // Already reported; just let this gesture cycle end cleanly
                // now that the pointer has been released.
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
            // This is the one spot that still races a timeout against a
            // suspend call, but awaitFirstDown (unlike awaitPointerEvent
            // mid-gesture) starts a fresh wait with no prior pointer state
            // to corrupt, so cancelling it on timeout is safe.
            val secondDown = withTimeoutOrNull(GestureTuning.DOUBLE_TAP_WINDOW_MS) {
                awaitFirstDown(pass = PointerEventPass.Main)
            }
            if (secondDown != null) {
                onGesture(GestureEvent.DoubleTap)
                // Consume the second tap's release so it doesn't leak into
                // the next gesture as a stray single tap.
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == secondDown.id } ?: break
                    if (change.changedToUp()) break
                }
                return@awaitEachGesture
            }
            onGesture(GestureEvent.SingleTap)
        }
    }
}
