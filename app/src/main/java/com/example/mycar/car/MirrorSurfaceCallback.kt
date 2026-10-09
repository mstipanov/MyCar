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
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.example.mycar.R
import com.example.mycar.ScaleMode
import com.example.mycar.Settings
import com.example.mycar.capture.FrameStore
import com.example.mycar.launch.LaunchTarget
import com.example.mycar.launch.QuickLaunch
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
 * conservative [onStableAreaChanged]), so the frame is fitted into the visible region rather
 * than the whole surface. How it is fitted is the user's [ScaleMode]: [ScaleMode.FIT] shows the
 * whole screen with black bars, the other modes scale it up and let the overflow be cropped.
 */
class MirrorSurfaceCallback(private val context: Context) : SurfaceCallback {

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sourceRect = Rect()
    private val destinationRect = RectF()

    /** Scratch rect for the touch-mapping path, which must not clobber [destinationRect]. */
    private val scratchRect = RectF()

    private var container: SurfaceContainer? = null

    /** Region of the surface the host says is visible right now. */
    private var visibleArea: Rect? = null

    /** Region guaranteed not to be covered by transient host UI; fallback for [visibleArea]. */
    private var stableArea: Rect? = null

    private var rendering = false

    /** Whether the quick launch drawer is on screen right now. */
    private var menuVisible = false

    /** Hides the drawer once the user stops tapping it; re-armed by every tap. */
    private val hideMenu = Runnable { menuVisible = false }

    /** Fill behind the drawer's rows. */
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_COLOR }

    /** Labels inside the drawer; the text size is set from the surface density when drawing. */
    private val menuTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Drawer title, resolved once. */
    private val menuTitle by lazy { context.getString(R.string.quick_launch_title) }

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
     * A tap on the car screen. The quick launch drawer gets first refusal: a tap anywhere brings
     * it up (and still taps the phone, when touch control is on), a tap on a row opens that app,
     * and a tap on the drawer's own background is swallowed. Everything the drawer does not claim
     * is forwarded to the phone through the same letterbox transform [drawFrame] uses, so tapping
     * a button in the mirror presses the same button on the phone. A forwarded tap also re-anchors
     * the drag cursor.
     */
    override fun onClick(x: Float, y: Float) {
        if (handleMenuTap(x, y)) return
        forwardTap(x, y)
    }

    /**
     * Runs the quick launch drawer's click handling. Returns true when the tap was consumed by the
     * drawer and so must not reach the phone.
     */
    private fun handleMenuTap(x: Float, y: Float): Boolean {
        val surfaceContainer = container ?: return false
        if (!Settings.quickLaunch(context)) return false

        val area = drawingArea(surfaceContainer.width, surfaceContainer.height)
        if (menuVisible) {
            val layout = menuLayout(area, density(surfaceContainer))
            val target = layout.rows.firstOrNull { it.first.contains(x, y) }?.second
            if (target != null) {
                QuickLaunch.open(context, target.packageName)
                dismissMenu()
                return true
            }
            if (layout.panel.contains(x, y)) {
                // A tap on the drawer's background: keep it up, but do not tap through it.
                showMenu()
                return true
            }
        }
        // A tap anywhere else reveals the drawer (or keeps it up) and is forwarded to the phone.
        showMenu()
        return false
    }

    private fun forwardTap(x: Float, y: Float) {
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

    /** Shows the drawer and restarts its auto-hide countdown. */
    private fun showMenu() {
        menuVisible = true
        handler.removeCallbacks(hideMenu)
        handler.postDelayed(hideMenu, MENU_HIDE_MS)
    }

    private fun dismissMenu() {
        menuVisible = false
        handler.removeCallbacks(hideMenu)
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

    /** Scale currently applied (surface pixels per captured phone pixel). */
    private fun pictureScale(): Float? {
        val surfaceContainer = container ?: return null
        val picture = FrameStore.pictureSize() ?: return null
        val area = drawingArea(surfaceContainer.width, surfaceContainer.height)
        computeDestination(area, picture.x, picture.y, scratchRect)
        if (scratchRect.isEmpty) return null
        val scale = scratchRect.width() / picture.x
        return if (scale > 0f) scale else null
    }

    // endregion

    /** Stops the render loop. Safe to call more than once. */
    fun stop() {
        rendering = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hideMenu)
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
     * Fills [out] with where the [pictureWidth] x [pictureHeight] frame is drawn inside [area]
     * under the user's [ScaleMode]. [ScaleMode.FIT] keeps the whole frame inside; the FILL modes
     * scale it up so it covers the area and let the overflow be clipped. Empty when either the
     * frame or the area is degenerate.
     */
    private fun computeDestination(area: Rect, pictureWidth: Int, pictureHeight: Int, out: RectF) {
        out.setEmpty()
        val areaWidth = area.width().toFloat()
        val areaHeight = area.height().toFloat()
        if (pictureWidth <= 0 || pictureHeight <= 0 || areaWidth <= 0f || areaHeight <= 0f) return

        val scale = when (Settings.scaleMode(context)) {
            ScaleMode.FIT -> minOf(areaWidth / pictureWidth, areaHeight / pictureHeight)
            ScaleMode.FILL_HEIGHT -> areaHeight / pictureHeight
            ScaleMode.FILL_WIDTH -> areaWidth / pictureWidth
            ScaleMode.FILL -> maxOf(areaWidth / pictureWidth, areaHeight / pictureHeight)
        }
        val drawnWidth = pictureWidth * scale
        val drawnHeight = pictureHeight * scale
        val left = area.left + (areaWidth - drawnWidth) / 2f
        val top = area.top + (areaHeight - drawnHeight) / 2f
        out.set(left, top, left + drawnWidth, top + drawnHeight)
    }

    /**
     * Inverse of the transform in [drawFrame]: a point in surface pixels to a point in captured
     * phone pixels. Null when the point falls outside the drawn picture (the black bars in
     * [ScaleMode.FIT], or when nothing is captured yet), so a tap there is ignored rather than
     * landing somewhere random.
     */
    private fun mapToPhone(x: Float, y: Float): PointF? {
        val surfaceContainer = container ?: return null
        val picture = FrameStore.pictureSize() ?: return null

        val area = drawingArea(surfaceContainer.width, surfaceContainer.height)
        computeDestination(area, picture.x, picture.y, scratchRect)
        if (scratchRect.isEmpty) return null
        if (!scratchRect.contains(x, y)) return null

        val scale = scratchRect.width() / picture.x
        if (scale <= 0f) return null
        return PointF((x - scratchRect.left) / scale, (y - scratchRect.top) / scale)
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

        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (t: Throwable) {
            Log.w(TAG, "lockCanvas failed", t)
            return
        } ?: return

        try {
            canvas.drawColor(Color.BLACK)

            // Fit the frame into the area the host allows us, per the user's scale mode.
            // The FILL modes deliberately overdraw the area; the canvas clips the overflow.
            FrameStore.withFrame { bitmap, pictureWidth, pictureHeight ->
                if (pictureWidth <= 0 || pictureHeight <= 0) return@withFrame
                computeDestination(area, pictureWidth, pictureHeight, destinationRect)
                if (!destinationRect.isEmpty) {
                    sourceRect.set(0, 0, pictureWidth, pictureHeight)
                    canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
                }
            }

            // Drawn after the frame (and without the FrameStore lock, which is released above), so
            // the drawer also shows while capture has not produced a frame yet.
            if (menuVisible && Settings.quickLaunch(context)) {
                drawMenu(canvas, area, density(surfaceContainer))
            }
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.w(TAG, "unlockCanvasAndPost failed", t)
            }
        }
    }

    // region quick launch drawer

    /** Panel plus the tappable row for each launch target, both in surface pixels. */
    private data class MenuLayout(val panel: RectF, val rows: List<Pair<RectF, LaunchTarget>>)

    /** Paints the drawer: a dark panel on the right with one icon + label row per target. */
    private fun drawMenu(canvas: Canvas, area: Rect, density: Float) {
        val layout = menuLayout(area, density)
        canvas.drawRoundRect(
            layout.panel,
            px(PANEL_CORNER_DP, density),
            px(PANEL_CORNER_DP, density),
            panelPaint,
        )

        val padding = px(MENU_PADDING_DP, density)
        menuTextPaint.textSize = px(MENU_TEXT_DP, density)
        val baseline = layout.panel.top + padding + menuTextPaint.textSize
        canvas.drawText(menuTitle, layout.panel.left + padding, baseline, menuTextPaint)

        val iconSize = px(MENU_ICON_DP, density)
        val gap = px(MENU_ICON_GAP_DP, density)
        val maxTextWidth = (layout.panel.width() - padding * 2 - iconSize - gap).coerceAtLeast(0f)

        for ((row, target) in layout.rows) {
            val iconLeft = row.left + padding
            val iconTop = row.centerY() - iconSize / 2f
            val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            target.icon?.let { canvas.drawBitmap(it, null, iconRect, paint) }

            val textX = iconRect.right + gap
            val textBaseline = row.centerY() - (menuTextPaint.ascent() + menuTextPaint.descent()) / 2f
            val label = TextUtils.ellipsize(
                target.label,
                menuTextPaint,
                maxTextWidth,
                TextUtils.TruncateAt.END,
            )
            canvas.drawText(label.toString(), textX, textBaseline, menuTextPaint)
        }
    }

    /** Where the drawer and its rows sit for the current area and density. */
    private fun menuLayout(area: Rect, density: Float): MenuLayout {
        val panelWidth = (area.width() * PANEL_WIDTH_FRACTION)
            .coerceAtMost(px(PANEL_MAX_WIDTH_DP, density))
        val panel = RectF(
            area.right - panelWidth,
            area.top.toFloat(),
            area.right.toFloat(),
            area.bottom.toFloat(),
        )

        val headerHeight = px(MENU_HEADER_DP, density)
        val rowHeight = px(MENU_ROW_DP, density)
        val rows = ArrayList<Pair<RectF, LaunchTarget>>()
        var top = panel.top + headerHeight
        for (target in QuickLaunch.targets(context)) {
            rows += RectF(panel.left, top, panel.right, top + rowHeight) to target
            top += rowHeight
        }
        return MenuLayout(panel, rows)
    }

    /** Surface pixels per dp. The host reports the surface dpi; assume mdpi if it does not. */
    private fun density(surfaceContainer: SurfaceContainer): Float =
        (surfaceContainer.dpi / 160f).coerceAtLeast(0.5f)

    private fun px(dp: Float, density: Float) = dp * density

    // endregion

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

        // region quick launch drawer
        /** Drawer width as a fraction of the drawing area, capped by [PANEL_MAX_WIDTH_DP]. */
        const val PANEL_WIDTH_FRACTION = 0.32f
        const val PANEL_MAX_WIDTH_DP = 240f
        const val PANEL_CORNER_DP = 16f
        const val MENU_PADDING_DP = 16f
        const val MENU_HEADER_DP = 56f
        const val MENU_ROW_DP = 64f
        const val MENU_ICON_DP = 40f
        const val MENU_TEXT_DP = 15f
        const val MENU_ICON_GAP_DP = 16f

        /** How long the drawer stays up after the last tap on it. */
        const val MENU_HIDE_MS = 4000L

        /** 90%-opaque near-black behind the drawer rows. */
        val PANEL_COLOR = 0xE61A1A1A.toInt()
        // endregion
    }
}
