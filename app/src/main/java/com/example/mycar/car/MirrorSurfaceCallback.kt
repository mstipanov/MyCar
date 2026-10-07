package com.example.mycar.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.example.mycar.Settings
import com.example.mycar.capture.FrameStore
import com.example.mycar.touch.TouchInjectorService

/**
 * Paints captured phone frames onto the Surface that Android Auto hands to navigation apps
 * for map drawing, and forwards gestures on that surface back to the phone: taps, pans and
 * pinch-to-zoom. The host exposes no touch-down position, so pans ride a virtual finger (see
 * [cursor]); only taps and pinch focal points arrive with real coordinates.
 *
 * The host only gives us that Surface because this app declares the NAVIGATION category and
 * ACCESS_SURFACE. Nothing here is a map: the "map layer" is just an image, which is the whole
 * trick behind this app.
 *
 * The surface is not always fully visible: opening the media or assistant side panel shrinks
 * the area the map may use. The host reports that through [onVisibleAreaChanged] (and the more
 * conservative [onStableAreaChanged]), so the frame is letterboxed into the visible region
 * rather than the whole surface. Without this the mirror keeps its full size and the panel
 * simply covers part of it.
 */
class MirrorSurfaceCallback(private val context: Context) : SurfaceCallback {

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sourceRect = Rect()
    private val destinationRect = RectF()

    private var container: SurfaceContainer? = null

    /** Region of the surface the host says is visible right now. */
    private var visibleArea: Rect? = null

    /** Region guaranteed not to be covered by transient host UI; fallback for [visibleArea]. */
    private var stableArea: Rect? = null

    private var rendering = false

    /**
     * Where a drag would land on the phone, in captured pixels. The car never tells us where a
     * drag began (only per-event deltas), so panning moves this "virtual finger" instead. It is
     * re-anchored by every tap and reset to the screen centre after a pause between drags.
     */
    private var cursor: PointF? = null
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var lastPanAt = 0L
    private val resetCursor = Runnable { cursor = null }

    private val tick = object : Runnable {
        override fun run() {
            drawFrame()
            if (rendering) handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        // A different Surface means the host rebuilt it, so any previously reported area is
        // stale until the host sends a new one.
        if (container?.surface !== surfaceContainer.surface) {
            visibleArea = null
            stableArea = null
        }
        container = surfaceContainer
        if (!rendering) {
            rendering = true
            handler.post(tick)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (container?.surface === surfaceContainer.surface) {
            stop()
            container = null
            visibleArea = null
            stableArea = null
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "visible area changed: $visibleArea")
        this.visibleArea = Rect(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "stable area changed: $stableArea")
        this.stableArea = Rect(stableArea)
    }

    // region touch forwarding

    /**
     * A tap on the car screen. The coordinates are surface pixels; they are mapped through the
     * same letterbox transform [drawFrame] uses and injected on the phone, so tapping a button in
     * the mirror presses the same button on the phone. A tap also re-anchors the drag cursor.
     */
    override fun onClick(x: Float, y: Float) {
        if (!Settings.touchControl(context)) return
        val point = mapToPhone(x, y)
        if (point == null) {
            Log.d(TAG, "tap at $x,$y is outside the mirrored picture")
            return
        }
        cursor = point
        Log.d(TAG, "tap $x,$y -> phone ${point.x},${point.y}")
        if (!TouchInjectorService.tap(point.x, point.y)) {
            Log.w(TAG, "tap not injected: accessibility service is not enabled")
        }
    }

    /**
     * A pan on the car screen. The host only reports deltas, so the movement is applied to the
     * virtual [cursor] and injected as a short drag. Steps are batched to about one dispatch per
     * frame; see [PAN_SIGN] if the direction turns out inverted on a given car.
     */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!Settings.touchControl(context)) return
        val scale = pictureScale() ?: return
        pendingDx += PAN_SIGN * distanceX / scale
        pendingDy += PAN_SIGN * distanceY / scale
        if (SystemClock.uptimeMillis() - lastPanAt < PAN_DISPATCH_MS) return
        dispatchPan()
    }

    /**
     * A fling on the car screen. Best effort: the scroll stream already moved the cursor, so this
     * just carries it a little further in the direction of travel.
     */
    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!Settings.touchControl(context)) return
        dispatchPan()
        val picture = FrameStore.pictureSize() ?: return
        val scale = pictureScale() ?: return
        val start = cursor ?: PointF(picture.x / 2f, picture.y / 2f)
        val dx = PAN_SIGN * velocityX / scale * FLING_SECONDS
        val dy = PAN_SIGN * velocityY / scale * FLING_SECONDS
        val end = clamp(start, dx, dy, picture)
        cursor = end
        scheduleCursorReset()
        TouchInjectorService.swipe(start.x, start.y, end.x, end.y, FLING_DURATION_MS)
    }

    /**
     * A pinch on the car screen. Unlike a pan, the host supplies the focal point, so this maps
     * cleanly onto the phone and injects a real two-finger pinch.
     */
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!Settings.touchControl(context)) return
        if (scaleFactor <= 0f || scaleFactor == 1f) return
        val picture = FrameStore.pictureSize() ?: return
        val focus = if (focusX >= 0f && focusY >= 0f) mapToPhone(focusX, focusY) else null
        val point = focus ?: PointF(picture.x / 2f, picture.y / 2f)
        if (!TouchInjectorService.pinch(point.x, point.y, scaleFactor)) {
            Log.w(TAG, "pinch not injected: accessibility service is not enabled")
        }
    }

    /** Applies any accumulated pan as one drag step and moves the virtual finger. */
    private fun dispatchPan() {
        val picture = FrameStore.pictureSize() ?: return
        if (pendingDx == 0f && pendingDy == 0f) return
        val dx = pendingDx
        val dy = pendingDy
        pendingDx = 0f
        pendingDy = 0f
        val start = cursor ?: PointF(picture.x / 2f, picture.y / 2f)
        val end = clamp(start, dx, dy, picture)
        cursor = end
        lastPanAt = SystemClock.uptimeMillis()
        scheduleCursorReset()
        if (!TouchInjectorService.swipe(start.x, start.y, end.x, end.y, PAN_DURATION_MS)) {
            Log.w(TAG, "pan not injected: accessibility service is not enabled")
        }
    }

    private fun clamp(start: PointF, dx: Float, dy: Float, picture: android.graphics.Point): PointF =
        PointF(
            (start.x + dx).coerceIn(0f, picture.x.toFloat()),
            (start.y + dy).coerceIn(0f, picture.y.toFloat()),
        )

    /** After a drag finishes, the next one starts fresh from the centre. */
    private fun scheduleCursorReset() {
        handler.removeCallbacks(resetCursor)
        handler.postDelayed(resetCursor, PAN_RESET_MS)
    }

    /** Letterbox scale currently applied (surface pixels per captured phone pixel). */
    private fun pictureScale(): Float? {
        val surfaceContainer = container ?: return null
        val picture = FrameStore.pictureSize() ?: return null
        val area = drawingArea(surfaceContainer.width, surfaceContainer.height)
        if (area.width() <= 0 || area.height() <= 0) return null
        val scale = minOf(area.width().toFloat() / picture.x, area.height().toFloat() / picture.y)
        return if (scale > 0f) scale else null
    }

    // endregion

    /** Stops the render loop. Safe to call more than once. */
    fun stop() {
        rendering = false
        handler.removeCallbacks(tick)
    }

    /**
     * The part of the [width] x [height] surface the frame may use, in surface pixels. Falls
     * back to the whole surface when the host has not reported an area (or reported an empty
     * one, or one outside the surface).
     */
    private fun drawingArea(width: Int, height: Int): Rect {
        val reported = visibleArea ?: stableArea
        if (reported != null) {
            val clamped = Rect(reported)
            if (clamped.width() > 0 && clamped.height() > 0 &&
                clamped.intersect(0, 0, width, height)
            ) {
                return clamped
            }
        }
        return Rect(0, 0, width, height)
    }

    /**
     * Inverse of the transform in [drawFrame]: a point in surface pixels to a point in captured
     * phone pixels. Null when the point falls in the letterbox bars (or nothing is captured yet),
     * so a tap on the black surround is ignored rather than landing somewhere random.
     */
    private fun mapToPhone(x: Float, y: Float): PointF? {
        val surfaceContainer = container ?: return null
        val picture = FrameStore.pictureSize() ?: return null

        val area = drawingArea(surfaceContainer.width, surfaceContainer.height)
        val areaWidth = area.width().toFloat()
        val areaHeight = area.height().toFloat()
        if (areaWidth <= 0f || areaHeight <= 0f) return null

        val scale = minOf(areaWidth / picture.x, areaHeight / picture.y)
        if (scale <= 0f) return null

        val drawnWidth = picture.x * scale
        val drawnHeight = picture.y * scale
        val left = area.left + (areaWidth - drawnWidth) / 2f
        val top = area.top + (areaHeight - drawnHeight) / 2f

        if (x < left || x > left + drawnWidth || y < top || y > top + drawnHeight) return null

        return PointF((x - left) / scale, (y - top) / scale)
    }

    private fun drawFrame() {
        val surfaceContainer = container ?: return
        val surface = surfaceContainer.surface ?: return
        if (!surface.isValid) return

        val width = surfaceContainer.width
        val height = surfaceContainer.height
        if (width <= 0 || height <= 0) return

        val area = drawingArea(width, height)
        val areaWidth = area.width()
        val areaHeight = area.height()
        if (areaWidth <= 0 || areaHeight <= 0) return

        FrameStore.withFrame { bitmap, pictureWidth, pictureHeight ->
            if (pictureWidth <= 0 || pictureHeight <= 0) return@withFrame

            val canvas: Canvas = try {
                surface.lockCanvas(null)
            } catch (t: Throwable) {
                Log.w(TAG, "lockCanvas failed", t)
                return@withFrame
            } ?: return@withFrame

            try {
                canvas.drawColor(Color.BLACK)

                // Letterbox the phone's aspect ratio inside the area the host allows us.
                // Anything else would either distort the picture or crop off part of the screen.
                val scale = minOf(
                    areaWidth.toFloat() / pictureWidth,
                    areaHeight.toFloat() / pictureHeight
                )
                val drawnWidth = pictureWidth * scale
                val drawnHeight = pictureHeight * scale
                val left = area.left + (areaWidth - drawnWidth) / 2f
                val top = area.top + (areaHeight - drawnHeight) / 2f

                sourceRect.set(0, 0, pictureWidth, pictureHeight)
                destinationRect.set(left, top, left + drawnWidth, top + drawnHeight)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (t: Throwable) {
                    Log.w(TAG, "unlockCanvasAndPost failed", t)
                }
            }
        }
    }

    private companion object {
        const val TAG = "MirrorSurfaceCallback"
        const val FRAME_INTERVAL_MS = 33L

        /**
         * Pan direction. The host only reports "distance scrolled", whose sign is ambiguous; flip
         * to -1f if panning comes out inverted on the car.
         */
        const val PAN_SIGN = 1f

        /** Batch pan deltas to at most one injected drag per this interval. */
        const val PAN_DISPATCH_MS = 40L
        const val PAN_DURATION_MS = 60L

        /** Quiet time after a drag before the virtual finger snaps back to the centre. */
        const val PAN_RESET_MS = 250L

        /** How much of a fling's velocity to carry into the injected drag. */
        const val FLING_SECONDS = 0.15f
        const val FLING_DURATION_MS = 120L
    }
}
