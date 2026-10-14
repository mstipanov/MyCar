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
 *  - Every swipe starts from the same anchor — the last tap, or the screen centre — so the pointer
 *    the phone sees never wanders away from where the user is working. (Walking the anchor across
 *    the screen, as the first cut did, drifts and gets pinned at the edges.)
 *  - A step is only emitted once the accumulated movement is worth a real drag ([MIN_STEP_PX]);
 *    tiny movements are held back, so a pan is never injected as a zero-length swipe, which the
 *    phone reads as a tap on whatever sits under the anchor.
 *  - Movement clamped at a screen edge is kept for the next step rather than dropped, so a long
 *    drag does not stall.
 *  - The anchor is held across the whole drag: a pause shorter than [IDLE_RESET_MS] does not
 *    reset it, so a slow drag does not jump half-way through.
 */
class PanController(
    private val minStepPx: Float = MIN_STEP_PX,
    private val stepDurationMs: Long = STEP_DURATION_MS,
    private val idleResetMs: Long = IDLE_RESET_MS,
) {

    /** Where the user last tapped, in phone pixels; the drag anchor, or null for the centre. */
    private var tapX: Float? = null
    private var tapY: Float? = null

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
     * Returns the swipe to inject, or null when the movement is not (yet) worth one or a swipe is
     * already in flight.
     */
    fun onScroll(
        distanceX: Float,
        distanceY: Float,
        scale: Float,
        pictureWidth: Int,
        pictureHeight: Int,
        now: Long,
    ): PanStep? {
        if (scale <= 0f || pictureWidth <= 0 || pictureHeight <= 0) return null
        if (now - lastActivityAt > idleResetMs) {
            pendingDx = 0f
            pendingDy = 0f
        }
        lastActivityAt = now
        width = pictureWidth
        height = pictureHeight
        pendingDx += distanceX / scale
        pendingDy += distanceY / scale
        return nextStep()
    }

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

    private fun nextStep(): PanStep? {
        if (inFlight || width <= 0 || height <= 0) return null
        val startX = anchorX()
        val startY = anchorY()
        val endX = (startX + pendingDx).coerceIn(0f, width.toFloat())
        val endY = (startY + pendingDy).coerceIn(0f, height.toFloat())
        val appliedX = endX - startX
        val appliedY = endY - startY
        if (hypot(appliedX, appliedY) < minStepPx) return null
        // Keep whatever the edge clamp left behind for the next step, so no movement is lost.
        pendingDx -= appliedX
        pendingDy -= appliedY
        inFlight = true
        return PanStep(startX, startY, endX, endY, stepDurationMs)
    }

    private fun anchorX(): Float =
        (tapX ?: width / 2f).coerceIn(width * ANCHOR_INSET, width * (1f - ANCHOR_INSET))

    private fun anchorY(): Float =
        (tapY ?: height / 2f).coerceIn(height * ANCHOR_INSET, height * (1f - ANCHOR_INSET))

    private companion object {
        /** Movement below this waits for more, so no swipe is a no-op (which reads as a tap). */
        const val MIN_STEP_PX = 24f

        /** How long each injected swipe takes. Long enough to read as a drag, not a tap. */
        const val STEP_DURATION_MS = 90L

        /** Quiet time that ends a drag, so the next one starts from a fresh anchor. */
        const val IDLE_RESET_MS = 400L

        /** Keep the anchor this far from an edge (fraction of the screen), so it can move both ways. */
        const val ANCHOR_INSET = 0.08f
    }
}
