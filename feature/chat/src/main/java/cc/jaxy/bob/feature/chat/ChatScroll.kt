package cc.jaxy.bob.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * Band around the newest content inside which the list keeps following the
 * tail (ChatGPT/Gemini semantics): a gesture that settles further away than
 * this pauses following; scrolling back into the band resumes it.
 */
private val TAIL_THRESHOLD = 72.dp

/**
 * Pixel distance from the bottom edge of the viewport to the tail of the
 * newest item. Items that are still below the fold are approximated with the
 * last visible item's height — precision only matters near the threshold,
 * where the trailing count is 0 or 1. [Int.MAX] when nothing is laid out yet.
 */
internal fun tailDistance(state: LazyListState): Float {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return Int.MAX_VALUE.toFloat()
    val gap = info.viewportEndOffset - (last.offset + last.size)
    val trailing = (info.totalItemsCount - 1 - last.index).coerceAtLeast(0)
    return (gap + trailing * last.size.coerceAtLeast(1)).toFloat()
}

/**
 * Pins the viewport to the bottom of the newest item.
 *
 * `scrollToItem(index)` aligns the item's *top* to the viewport top, which for
 * a last item taller than the viewport hides the tail behind the fold — a
 * growing streaming answer would appear to never advance. So: scroll the item
 * into view first, let one frame settle, then correct the remaining pixel gap
 * so the item's bottom edge sits on the viewport's bottom edge.
 */
internal suspend fun followTail(state: LazyListState, animate: Boolean) {
    val info = state.layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0) return
    if (info.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
        if (animate) state.animateScrollToItem(lastIndex) else state.scrollToItem(lastIndex)
        withFrameNanos { } // let the scroll-triggered layout land
    }
    val settled = state.layoutInfo
    val last = settled.visibleItemsInfo.lastOrNull() ?: return
    if (last.index != settled.totalItemsCount - 1) return
    val gap = settled.viewportEndOffset - (last.offset + last.size)
    if (gap >= -1) return // bottom edge already on/below the viewport bottom
    if (animate) state.animateScrollBy(-gap.toFloat()) else state.scroll { scrollBy(-gap.toFloat()) }
}

/**
 * Tail-follow controller for one list: owns the pinned flag and the gesture /
 * resize observers that flip it. `pinned` is the single source of truth for
 * "the user wants to see the newest content"; the caller's content effects
 * call [follow]/[pin] and UI reads [pinned].
 */
internal class TailFollow(
    val state: LazyListState,
    private val thresholdPx: Float,
) {
    var pinned: Boolean by mutableStateOf(true)
        private set

    /** Unconditionally re-follow the tail (send, jump-to-bottom, focus input). */
    fun pin() {
        pinned = true
    }

    /** Adopt the resting position as intent after a gesture settles. */
    fun settle() {
        pinned = tailDistance(state) <= thresholdPx
    }

    suspend fun follow(animate: Boolean) {
        followTail(state, animate)
    }
}

@Composable
internal fun rememberTailFollow(state: LazyListState): TailFollow {
    val thresholdPx = with(LocalDensity.current) { TAIL_THRESHOLD.toPx() }
    val tail = remember(state, thresholdPx) { TailFollow(state, thresholdPx) }
    // A settled gesture adopts the resting position as intent: a finger that
    // moves the tail out of the band unpins, scrolling back into the band
    // pins again. Programmatic follows don't toggle isScrollInProgress, so
    // they never clobber the user's intent.
    LaunchedEffect(tail) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress && state.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                    tail.settle()
                }
            }
    }
    // Keyboard/IME shrink re-pins the tail: the newest line stays clear of
    // the composer when the user was already following.
    LaunchedEffect(tail) {
        snapshotFlow { state.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                if (tail.pinned && !state.isScrollInProgress) {
                    tail.follow(animate = false)
                }
            }
    }
    return tail
}
