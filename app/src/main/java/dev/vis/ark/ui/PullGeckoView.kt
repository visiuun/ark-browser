package dev.vis.ark.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

/**
 * GeckoView with pull-to-refresh. When a finger goes down, Gecko reports whether the page is scrolled to
 * the top, whether the page itself handled the touch (maps, games and drawing apps call preventDefault),
 * and whether it allows overscrolling (CSS `overscroll-behavior: none` turns pull-to-refresh off, as in
 * Chrome). Only then does dragging down pull the refresh indicator.
 */
class PullGeckoView(ctx: Context) : GeckoView(ctx) {
    /** How far the page is being pulled, in px (0 = not pulling). */
    var onPull: (Float) -> Unit = {}
    /** The finger was lifted after pulling [distance] px. */
    var onRelease: (distance: Float) -> Unit = {}

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var startY = 0f
    private var startX = 0f
    private var canPull = false
    private var pulling = false
    private var distance = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y; startX = ev.x
                canPull = false; pulling = false; distance = 0f
                // Ask Gecko about this touch; the answer arrives before the finger has moved far.
                onTouchEventForDetailResult(ev).accept { d ->
                    if (d == null) return@accept
                    canPull = d.handledResult() != PanZoomController.INPUT_RESULT_HANDLED_CONTENT &&
                        d.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP == 0 &&
                        d.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL != 0
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> cancelPull()

            MotionEvent.ACTION_MOVE -> if (canPull && ev.pointerCount == 1) {
                val dy = ev.y - startY
                val dx = ev.x - startX
                if (!pulling && dy > slop && dy > kotlin.math.abs(dx) * 1.5f) pulling = true
                if (pulling) {
                    distance = (dy - slop).coerceAtLeast(0f)
                    onPull(distance)
                    if (dy < 0) cancelPull()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (pulling) onRelease(distance)
                pulling = false; canPull = false; distance = 0f
            }

            MotionEvent.ACTION_CANCEL -> cancelPull()
        }
        return super.onTouchEvent(ev)
    }

    private fun cancelPull() {
        if (pulling) onPull(0f)
        pulling = false; canPull = false; distance = 0f
    }
}
