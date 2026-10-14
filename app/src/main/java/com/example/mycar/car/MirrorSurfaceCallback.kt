package com.example.mycar.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import com.example.mycar.R
import com.example.mycar.RotationLock
import com.example.mycar.ScaleMode
import com.example.mycar.Settings
import com.example.mycar.capture.FrameStore
import com.example.mycar.launch.QuickLaunch
import com.example.mycar.touch.PanController
import com.example.mycar.touch.PanStep
import com.example.mycar.touch.TouchInjectorService

/**
 * Paints captured phone frames onto the Surface that Android Auto hands to navigation apps
 * for map drawing, and forwards gestures on that surface back to the phone: taps, pans and
 * pinch-to-zoom. The host exposes no touch-down position, so pans ride a virtual finger (see
 * [PanController]); only taps and pinch focal points arrive with real coordinates.
 *
 * The host only gives us that Surface because this app declares the NAVIGATION category and
 * ACCESS_SURFACE. Nothing here is a map: the "map layer" is just an image, which is the whole
 * trick behind this app.
 *
 * The mirror fills the whole map surface, edge to edge, the way native map apps draw. The host
 * also reports a smaller *visible* area through [onVisibleAreaChanged] (with the more conservative
 * [onStableAreaChanged] as a fallback) where its own chrome or a media/assistant panel covers the
 * surface; we draw under that chrome rather than shrink into it, because filling only the inset
 * left a black frame around the mirror that those apps do not show. Only while a panel is actually
 * open do we shrink to the visible area, so the mirror is not hidden behind it. How the frame is
 * scaled into the area is the user's [ScaleMode]: [ScaleMode.FIT] shows the whole screen with black
 * bars, the other modes scale it up and let the overflow be cropped.
 *
 * The icon-only quick launch strip gets its own space: a column on the right normally, or a row
 * along the bottom while a media/assistant panel is open. The mirror is fitted into what is left,
 * so the strip never covers it.
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

    /** Fill behind the quick launch strip. */
    private val launcherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_COLOR }

    /** Faint disc behind action cells (e.g. rotate) so they read as buttons, not apps. */
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACTION_COLOR }

    /** The rotate glyph, rasterized once. */
    private val rotateIcon: Bitmap? by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_rotate)?.let { rasterize(it) }
    }

    /**
     * Turns the car's pan stream into injectable drags. The car never tells us where a drag began
     * (only per-event deltas), so panning rides a virtual finger that [PanController] keeps on the
     * last tap point (or the screen centre) and never lets drift.
     */
    private val pan = PanController()

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
     * A tap on the car screen. A tap on the quick launch strip opens that app on the phone;
     * everything else is forwarded to the phone through the same letterbox transform [drawFrame]
     * uses, so tapping a button in the mirror presses the same button on the phone. A forwarded
     * tap also re-anchors the next drag to where the user touched.
     */
    override fun onClick(x: Float, y: Float) {
        val surfaceContainer = container
        if (surfaceContainer != null && launcherEnabled()) {
            val density = density(surfaceContainer)
            val area = drawingArea(surfaceContainer.width, surfaceContainer.height, density)
            val atBottom = launcherAtBottom(surfaceContainer.width, surfaceContainer.height, density)
            val entry = launcherLayout(area, density, atBottom).cells
                .firstOrNull { it.first.contains(x, y) }
                ?.second
            if (entry != null) {
                entry.activate()
                return
            }
        }
        forwardTap(x, y)
    }

    private fun forwardTap(x: Float, y: Float) {
        if (!Settings.touchControl(context)) return
        val point = mapToPhone(x, y)
        if (point == null) {
            Log.d(TAG, "tap at $x,$y is outside the mirrored picture")
            return
        }
        pan.onTap(point.x, point.y)
        Log.d(TAG, "tap $x,$y -> phone ${point.x},${point.y}")
        if (!TouchInjectorService.tap(point.x, point.y)) {
            Log.w(TAG, "tap not injected: accessibility service is not enabled")
        }
    }

    /**
     * A pan on the car screen. The host only reports deltas, so the movement is accumulated and
     * injected as short drags from a fixed anchor (the last tap, or the screen centre) — see
     * [PanController]. Swipes are serialized: the next is only injected once the previous one
     * finished, so none is cancelled mid-move. See [PAN_SIGN] if the direction is inverted on a
     * given car.
     */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!Settings.touchControl(context)) return
        val picture = FrameStore.pictureSize() ?: return
        val scale = pictureScale() ?: return
        // A floating (Picture-in-Picture) window wins: the car gives no touch-down point, so a drag
        // would otherwise land on the centre and move whatever is behind the window.
        val floating = floatingAnchor()
        val step = pan.onScroll(
            distanceX = PAN_SIGN * distanceX,
            distanceY = PAN_SIGN * distanceY,
            scale = scale,
            pictureWidth = picture.x,
            pictureHeight = picture.y,
            now = SystemClock.uptimeMillis(),
            anchorX = floating?.x,
            anchorY = floating?.y,
        )
        injectPan(step)
    }

    /**
     * A fling on the car screen. The scroll stream already moved the mirror, so this only ends the
     * drag — adding another swipe here is what used to make a drag finish with an overshoot.
     */
    override fun onFling(velocityX: Float, velocityY: Float) {
        pan.onFling(SystemClock.uptimeMillis())
    }

    /**
     * A pinch on the car screen. Unlike a pan, the host supplies the focal point, so this maps
     * cleanly onto the phone and injects a real two-finger pinch. Pinching also re-anchors the next
     * drag to the pinch point.
     */
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!Settings.touchControl(context)) return
        if (scaleFactor <= 0f || scaleFactor == 1f) return
        val picture = FrameStore.pictureSize() ?: return
        val focus = if (focusX >= 0f && focusY >= 0f) mapToPhone(focusX, focusY) else null
        val point = focus ?: PointF(picture.x / 2f, picture.y / 2f)
        pan.onTap(point.x, point.y)
        if (!TouchInjectorService.pinch(point.x, point.y, scaleFactor)) {
            Log.w(TAG, "pinch not injected: accessibility service is not enabled")
        }
    }

    /** Injects one accumulated pan step, then the next one (if any) once it has finished. */
    private fun injectPan(step: PanStep?) {
        if (step == null) return
        val injected = TouchInjectorService.swipe(
            step.startX, step.startY, step.endX, step.endY, step.durationMs,
        ) {
            injectPan(pan.onGestureComplete())
        }
        if (!injected) {
            Log.w(TAG, "pan not injected: accessibility service is not enabled")
            // The completion callback will never run, so release the controller here or panning
            // deadlocks on the in-flight flag. Drop the pending movement with it.
            pan.onFling(SystemClock.uptimeMillis())
            pan.onGestureComplete()
        }
    }

    /**
     * Centre of the floating Picture-in-Picture window in phone pixels, or null when there is none.
     * The capture is at the real display size, so the window's screen bounds are also its bounds in
     * the mirrored picture. When this is non-null, panning anchors here so a drag grabs the window
     * rather than the app behind it.
     */
    private fun floatingAnchor(): PointF? {
        val bounds = TouchInjectorService.pictureInPictureBounds() ?: return null
        if (bounds.isEmpty) return null
        return PointF(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** Scale currently applied (surface pixels per captured phone pixel). */
    private fun pictureScale(): Float? {
        val surfaceContainer = container ?: return null
        val picture = FrameStore.pictureSize() ?: return null
        val density = density(surfaceContainer)
        val area = drawingArea(surfaceContainer.width, surfaceContainer.height, density)
        val content = contentArea(area, density, launcherAtBottom(surfaceContainer.width, surfaceContainer.height, density))
        computeDestination(content, picture.x, picture.y, scratchRect)
        if (scratchRect.isEmpty) return null
        val scale = scratchRect.width() / picture.x
        return if (scale > 0f) scale else null
    }

    // endregion

    /** Stops the render loop. Safe to call more than once. */
    fun stop() {
        rendering = false
        handler.removeCallbacks(tick)
    }

    /**
     * The part of the [width] x [height] surface the host says is visible right now, in surface
     * pixels: [visibleArea] if set, else the more conservative [stableArea]. Falls back to the
     * whole surface when the host has not reported an area (or reported an empty one, or one
     * outside the surface).
     */
    private fun reportedArea(width: Int, height: Int): Rect {
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
     * The region the frame is drawn into, and the region its touch transform maps back from.
     *
     * Normally the whole surface, edge to edge. The host insets the *visible* area for its own
     * chrome (its status bar, its app rail) as well as for a media/assistant panel; a native map
     * app draws under that chrome, but filling only the inset left MyCar with the black frame
     * around the mirror those apps do not show. So the mirror fills the surface, and its overflow
     * is cropped per the [ScaleMode]. While a panel is actually covering part of the surface we do
     * still fall back to the visible area, so the mirror is not hidden behind the panel.
     */
    private fun drawingArea(width: Int, height: Int, density: Float): Rect {
        val reported = reportedArea(width, height)
        return if (panelShown(reported, density)) reported else Rect(0, 0, width, height)
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

        val density = density(surfaceContainer)
        val area = drawingArea(surfaceContainer.width, surfaceContainer.height, density)
        val content = contentArea(area, density, launcherAtBottom(surfaceContainer.width, surfaceContainer.height, density))
        computeDestination(content, picture.x, picture.y, scratchRect)
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

        val density = density(surfaceContainer)
        val area = drawingArea(width, height, density)
        val areaWidth = area.width()
        val areaHeight = area.height()
        if (areaWidth <= 0 || areaHeight <= 0) return

        val atBottom = launcherAtBottom(width, height, density)
        val content = contentArea(area, density, atBottom)

        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (t: Throwable) {
            Log.w(TAG, "lockCanvas failed", t)
            return
        } ?: return

        try {
            canvas.drawColor(Color.BLACK)

            // Fit the frame into the area left for it, per the user's scale mode. The FILL modes
            // deliberately overdraw the area; the canvas clips the overflow.
            FrameStore.withFrame { bitmap, pictureWidth, pictureHeight ->
                if (pictureWidth <= 0 || pictureHeight <= 0) return@withFrame
                computeDestination(content, pictureWidth, pictureHeight, destinationRect)
                if (!destinationRect.isEmpty) {
                    sourceRect.set(0, 0, pictureWidth, pictureHeight)
                    canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
                }
            }

            // Drawn after the frame and without the FrameStore lock, so the strip also shows while
            // capture has not produced a frame yet.
            if (launcherEnabled()) {
                drawLauncher(canvas, area, density, atBottom)
            }
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.w(TAG, "unlockCanvasAndPost failed", t)
            }
        }
    }

    // region quick launch launcher

    /** One tappable cell: an app icon to open, or a built-in action like rotate. */
    private class LauncherEntry(
        val icon: Bitmap?,
        val isAction: Boolean,
        val activate: () -> Unit,
    )

    /** The launcher strip plus the tappable cell for each entry, both in surface pixels. */
    private data class LauncherLayout(val strip: RectF, val cells: List<Pair<RectF, LauncherEntry>>)

    /** True when the user wants the car-side launcher at all. */
    private fun launcherEnabled(): Boolean = Settings.quickLaunch(context)

    /** The launcher cells: the app targets, then the rotate action. Cheap to rebuild each frame. */
    private fun launcherEntries(): List<LauncherEntry> {
        val entries = ArrayList<LauncherEntry>()
        for (target in QuickLaunch.targets(context)) {
            entries += LauncherEntry(target.icon, isAction = false) {
                QuickLaunch.open(context, target.packageName)
            }
        }
        rotateIcon?.let { icon ->
            entries += LauncherEntry(icon, isAction = true) { rotateScreen() }
        }
        return entries
    }

    /** Toggles the mirrored phone between portrait and landscape. */
    private fun rotateScreen() {
        val rotated = RotationLock.togglePortraitLandscape(context)
        Log.i(TAG, "rotate tapped; display rotated = $rotated")
        if (!rotated) {
            Log.w(TAG, "rotate: \"Modify system settings\" is not granted; opening it")
            runCatching { context.startActivity(RotationLock.permissionIntent(context)) }
                .onFailure { Log.w(TAG, "Could not open the write-settings screen", it) }
        }
    }

    /** Rasterizes a vector drawable to a square bitmap for the strip. */
    private fun rasterize(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(ACTION_ICON_PX, ACTION_ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ACTION_ICON_PX, ACTION_ICON_PX)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Whether a transient host panel (media/assistant) is covering part of the surface right now.
     * The host reflects that by reporting a [stableArea] smaller than the visible area; with no
     * panel open the two match. This decides whether the launcher sits on the side or the bottom.
     */
    private fun panelShown(area: Rect, density: Float): Boolean {
        val stable = stableArea ?: return false
        val tolerance = px(6f, density)
        return stable.width() < area.width() - tolerance ||
            stable.height() < area.height() - tolerance
    }

    /**
     * Whether the quick launch strip should sit along the bottom: true while a panel covers part
     * of the surface. Measured against the host's *visible* area, not the area the mirror fills.
     */
    private fun launcherAtBottom(width: Int, height: Int, density: Float): Boolean =
        panelShown(reportedArea(width, height), density)

    /**
     * The part of [area] the mirror may use once the launcher strip is carved out, so the strip
     * never covers the mirror. [area] itself when the launcher is off.
     */
    private fun contentArea(area: Rect, density: Float, atBottom: Boolean): Rect {
        if (!launcherEnabled()) return area
        val thickness = px(LAUNCHER_THICKNESS_DP, density).toInt()
        return if (atBottom) {
            Rect(area.left, area.top, area.right, (area.bottom - thickness).coerceAtLeast(area.top))
        } else {
            Rect(area.left, area.top, (area.right - thickness).coerceAtLeast(area.left), area.bottom)
        }
    }

    /** The strip the launcher occupies: a column on the right, or a row along the bottom. */
    private fun launcherLayout(area: Rect, density: Float, atBottom: Boolean): LauncherLayout {
        val thickness = px(LAUNCHER_THICKNESS_DP, density)
        val strip = if (atBottom) {
            RectF(
                area.left.toFloat(),
                area.bottom - thickness,
                area.right.toFloat(),
                area.bottom.toFloat(),
            )
        } else {
            RectF(
                area.right - thickness,
                area.top.toFloat(),
                area.right.toFloat(),
                area.bottom.toFloat(),
            )
        }

        val entries = launcherEntries()
        val apps = entries.filter { !it.isAction }
        val actions = entries.filter { it.isAction }
        val cell = thickness
        val cells = ArrayList<Pair<RectF, LauncherEntry>>(entries.size)
        if (atBottom) {
            // Apps centred along the strip; actions pinned to the far (right) end.
            var left = strip.centerX() - cell * apps.size / 2f
            for (entry in apps) {
                cells += RectF(left, strip.top, left + cell, strip.bottom) to entry
                left += cell
            }
            var right = strip.right
            for (entry in actions) {
                cells += RectF(right - cell, strip.top, right, strip.bottom) to entry
                right -= cell
            }
        } else {
            // Apps centred in the column; actions pinned to the bottom.
            var top = strip.centerY() - cell * apps.size / 2f
            for (entry in apps) {
                cells += RectF(strip.left, top, strip.right, top + cell) to entry
                top += cell
            }
            var bottom = strip.bottom
            for (entry in actions) {
                cells += RectF(strip.left, bottom - cell, strip.right, bottom) to entry
                bottom -= cell
            }
        }
        return LauncherLayout(strip, cells)
    }

    /** Paints the strip: a dark bar of app icons only — no labels, no covering panel. */
    private fun drawLauncher(canvas: Canvas, area: Rect, density: Float, atBottom: Boolean) {
        val layout = launcherLayout(area, density, atBottom)
        canvas.drawRect(layout.strip, launcherPaint)

        val iconSize = px(LAUNCHER_ICON_DP, density)
        for ((cell, entry) in layout.cells) {
            val icon = entry.icon ?: continue
            val cx = cell.centerX()
            val cy = cell.centerY()
            if (entry.isAction) canvas.drawCircle(cx, cy, iconSize * 0.62f, actionPaint)
            canvas.drawBitmap(
                icon,
                null,
                RectF(cx - iconSize / 2f, cy - iconSize / 2f, cx + iconSize / 2f, cy + iconSize / 2f),
                paint,
            )
        }
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
         * Pan direction. The host reports "distance scrolled" with the opposite sign to the finger
         * movement we need to inject on the tested car; use 1f if panning comes out inverted on
         * another.
         */
        const val PAN_SIGN = -1f

        // Pan step timing, step size and anchor inset live in touch/PanController.

        // region quick launch strip
        /** Strip thickness in dp: its width on the right, or its height along the bottom. */
        const val LAUNCHER_THICKNESS_DP = 78f

        /** Icon edge inside a strip cell, in dp. */
        const val LAUNCHER_ICON_DP = 54f

        /** Near-black bar behind the icons. */
        val PANEL_COLOR = 0xF21B1B1B.toInt()

        /** Faint disc behind action cells so they read as buttons. */
        val ACTION_COLOR = 0x33FFFFFF

        /** The rotate glyph is rasterized at this edge, then scaled to the cell. */
        const val ACTION_ICON_PX = 128
        // endregion
    }
}
