package com.example.mycar.touch

import kotlin.math.hypot

/**
 * A drag to inject, in phone pixels: the accessibility pointer is placed at ([startX], [startY])
 * and moved to ([endX], [endY]) over [durationMs].
 */
data class PanStep(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long,
)

/**
 * Turns the car's pan stream into a sequence of injectable drags, free of Android so it can be
 * unit-tested.
 *
 * Android Auto reports a pan as a stream of movement deltas only — never a touch-down point — and
 * the accessibility API can only inject whole, atomic strokes (a down, a move, an up) that cannot
 * be streamed or continued. A drag therefore cannot be reproduced as one continuous gesture. This
 * class instead emits one short swipe per batch of movement, and the caller injects them one at a
 * time.
 *
 * What keeps a drag from misbehaving:
 *  - At most one swipe is in flight; the next is only handed out when [onGestureComplete] reports
 *    the previous one done. Overlapping strokes cancel each other mid-move, which drifts.
 *  - Every swipe starts from the same anchor — the last tap, the screen centre, or a floating
 *    window supplied by the caller — so the pointer the phone sees never wanders away from where
 *    the user is working. (Walking the anchor across the screen, as the first cut did, drifts and
 *    gets pinned at the edges.)
 *  - A step is only emitted once the accumulated movement is worth a real drag; tiny movements are
 *    held back, so a pan is never injected as a zero-length swipe, which the phone reads as a tap
 *    on whatever sits under the anchor. A floating window needs a bigger step to clear its own
 *    touch-slop, hence the separate [floatingMinStepPx].
 *  - Movement clamped at a screen edge is kept for the next step rather than dropped, so a long
 *    drag does not stall.
 *  - The anchor is held across the whole drag: a pause shorter than [IDLE_RESET_MS] does not
 *    reset it, so a slow drag does not jump half-way through.
 */
class PanController(
    private val minStepPx: Float = MIN_STEP_PX,
    private val floatingMinStepPx: Float = FLOATING_MIN_STEP_PX,
    private val stepDurationMs: Long = STEP_DURATION_MS,
    private val idleResetMs: Long = IDLE_RESET_MS,
) {

    /** Where the user last tapped, in phone pixels; the fallback drag anchor, or null for centre. */
    private var tapX: Float? = null
    private var tapY: Float? = null

    /** Origin of the current drag's swipes, in phone pixels. */
    private var anchorX = 0f
    private var anchorY = 0f

    /** Minimum step for the current drag: bigger while grabbing a floating window. */
    private var minStep = minStepPx

    /** Movement accumulated since the last emitted step, in phone pixels. */
    private var pendingDx = 0f
    private var pendingDy = 0f

    /** Whether a swipe is being injected; no second one may start until it ends. */
    private var inFlight = false

    private var lastActivityAt = 0L

    private var width = 0
    private var height = 0

    /** A tap re-anchors the next drag to where the user touched. */
    fun onTap(x: Float, y: Float) {
        tapX = x
        tapY = y
    }

    /**
     * A batch of pan movement in surface pixels. [scale] is surface pixels per phone pixel.
     *
     * [anchorX]/[anchorY], when both given, are the drag origin in phone pixels — used for a
     * floating window, whose position the caller can read and which wins over the last tap. Returns
     * the swipe to inject, or null when the movement is not (yet) worth one or a swipe is already
     * in flight.
     */
    fun onScroll(
        distanceX: Float,
        distanceY: Float,
        scale: Float,
        pictureWidth: Int,
        pictureHeight: Int,
        now: Long,
        anchorX: Float? = null,
        anchorY: Float? = null,
    ): PanStep? {
        if (scale <= 0f || pictureWidth <= 0 || pictureHeight <= 0) return null
        if (now - lastActivityAt > idleResetMs) {
            pendingDx = 0f
            pendingDy = 0f
        }
        lastActivityAt = now
        width = pictureWidth
        height = pictureHeight
        placeAnchor(anchorX, anchorY)
        pendingDx += distanceX / scale
        pendingDy += distanceY / scale
        return nextStep()
    }

    /**
     * Whether a pan arriving at [now] begins a fresh drag rather than continuing a paused one.
     * Callers use this to latch per-drag decisions (such as which window the drag is aimed at) to
     * exactly the same drag boundaries the controller itself uses.
     */
    fun startsNewDrag(now: Long): Boolean = now - lastActivityAt > idleResetMs

    /** The injected swipe finished; hand out the next accumulated step, if any. */
    fun onGestureComplete(): PanStep? {
        inFlight = false
        return nextStep()
    }

    /** The drag ended. Drop the tail rather than add a late swipe that would overshoot. */
    fun onFling(now: Long) {
        lastActivityAt = now
        pendingDx = 0f
        pendingDy = 0f
    }

    /** Chooses the drag origin for this batch: a floating window, the last tap, or the centre. */
    private fun placeAnchor(overrideX: Float?, overrideY: Float?) {
        if (overrideX != null && overrideY != null) {
            // A floating window: grab it where it actually is, without the edge inset (a small
            // window near an edge still has to be draggable), and ask for a bigger step.
            anchorX = overrideX.coerceIn(0f, width.toFloat())
            anchorY = overrideY.coerceIn(0f, height.toFloat())
            minStep = floatingMinStepPx
        } else {
            anchorX = (tapX ?: width / 2f).coerceIn(width * ANCHOR_INSET, width * (1f - ANCHOR_INSET))
            anchorY = (tapY ?: height / 2f).coerceIn(height * ANCHOR_INSET, height * (1f - ANCHOR_INSET))
            minStep = minStepPx
        }
    }

    private fun nextStep(): PanStep? {
        if (inFlight || width <= 0 || height <= 0) return null
        val endX = (anchorX + pendingDx).coerceIn(0f, width.toFloat())
        val endY = (anchorY + pendingDy).coerceIn(0f, height.toFloat())
        val appliedX = endX - anchorX
        val appliedY = endY - anchorY
        if (hypot(appliedX, appliedY) < minStep) return null
        // Keep whatever the edge clamp left behind for the next step, so no movement is lost.
        pendingDx -= appliedX
        pendingDy -= appliedY
        inFlight = true
        return PanStep(anchorX, anchorY, endX, endY, stepDurationMs)
    }

    private companion object {
        /** Movement below this waits for more, so no swipe is a no-op (which reads as a tap). */
        const val MIN_STEP_PX = 24f

        /** A floating window needs a bigger step to clear its own touch-slop and read as a drag. */
        const val FLOATING_MIN_STEP_PX = 48f

        /** How long each injected swipe takes. Long enough to read as a drag, not a tap. */
        const val STEP_DURATION_MS = 90L

        /** Quiet time that ends a drag, so the next one starts from a fresh anchor. */
        const val IDLE_RESET_MS = 400L

        /** Keep the anchor this far from an edge (fraction of the screen), so it can move both ways. */
        const val ANCHOR_INSET = 0.08f
    }
}
