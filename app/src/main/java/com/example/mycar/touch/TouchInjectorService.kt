package com.example.mycar.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.mycar.Settings

/**
 * Accessibility service with two jobs:
 *
 * - Inject taps the user makes on the car mirror, so the car touchscreen can drive the phone
 *   (`canPerformGestures`).
 * - Press Share on the system screen-capture prompt for hands-free start
 *   (`canRetrieveWindowContent`).
 *
 * A normal app cannot synthesize input for other apps; an accessibility service with gestures is
 * one of the two supported routes (root is the other). This service acts only when
 * [MirrorSurfaceCallback] forwards a tap, or on SystemUI's projection window — it never reads or
 * touches anything else. The user enables it manually in Settings -> Accessibility.
 */
class TouchInjectorService : AccessibilityService() {

    /** Debounce so the capture prompt is accepted once, not on every window change. */
    private var lastAutoAcceptAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        autoAcceptCapturePrompt(event)
    }

    override fun onInterrupt() = Unit

    /**
     * Presses "Share" on the system screen-capture prompt when auto-start is on, so the car can
     * bring capture up with no tap. Strictly scoped to SystemUI's projection window — every other
     * event is ignored, so the service never acts on unrelated UI.
     *
     * The primary hands-free path is the `PROJECT_MEDIA` app-op (see README), which makes the
     * prompt auto-grant; this is the fallback for phones where that has not been granted.
     */
    private fun autoAcceptCapturePrompt(event: AccessibilityEvent?) {
        val source = event ?: return
        if (!Settings.autoStart(this)) return
        if (source.packageName?.toString() != SYSTEMUI_PACKAGE) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoAcceptAt < AUTO_ACCEPT_COOLDOWN_MS) return
        val root = rootInActiveWindow ?: return
        val isProjectionDialog =
            source.className?.toString()?.contains("MediaProjection", ignoreCase = true) == true ||
                root.findAccessibilityNodeInfosByViewId(PROJECTION_DIALOG_ID).isNotEmpty()
        if (!isProjectionDialog) return
        val button = root.findAccessibilityNodeInfosByViewId(POSITIVE_BUTTON_ID).firstOrNull() ?: return
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastAutoAcceptAt = now
            Log.i(TAG, "accepted the screen-capture prompt")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        // Callback/Handler may be null: we do not care about the result beyond "accepted".
        return dispatchGesture(gesture, null, null)
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(
            GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(10L, 2_000L)),
        )
    }

    /**
     * A two-finger pinch centred on [x],[y]. [scaleFactor] is the incremental factor from the
     * host (current / previous), so >1 spreads the fingers (zoom in) and <1 brings them together.
     */
    private fun pinch(x: Float, y: Float, scaleFactor: Float): Boolean {
        val half = PINCH_HALF_SPAN_PX
        val endHalf = (half * scaleFactor).coerceIn(half * 0.25f, half * 4f)
        val left = Path().apply {
            moveTo(x - half, y)
            lineTo(x - endHalf, y)
        }
        val right = Path().apply {
            moveTo(x + half, y)
            lineTo(x + endHalf, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0L, PINCH_DURATION_MS))
            .addStroke(GestureDescription.StrokeDescription(right, 0L, PINCH_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "TouchInjectorService"
        private const val TAP_DURATION_MS = 60L

        /** Half the finger separation used for an injected pinch, in phone pixels. */
        private const val PINCH_HALF_SPAN_PX = 120f
        private const val PINCH_DURATION_MS = 160L

        /** SystemUI's screen-capture permission window and its confirm button. */
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val PROJECTION_DIALOG_ID = "com.android.systemui:id/screen_share_permission_dialog"
        private const val POSITIVE_BUTTON_ID = "android:id/button1"
        private const val AUTO_ACCEPT_COOLDOWN_MS = 2_000L

        @Volatile
        private var instance: TouchInjectorService? = null

        /** Whether the user has switched the service on in Settings. */
        val isConnected: Boolean get() = instance != null

        /** Injects a tap at the given phone pixel. Returns false if the service is not enabled. */
        fun tap(x: Float, y: Float): Boolean = instance?.tap(x, y) ?: false

        /** Injects a swipe between two phone pixels. Returns false if not enabled. */
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
            instance?.swipe(x1, y1, x2, y2, durationMs) ?: false

        /** Injects a two-finger pinch centred on a phone pixel. Returns false if not enabled. */
        fun pinch(x: Float, y: Float, scaleFactor: Float): Boolean =
            instance?.pinch(x, y, scaleFactor) ?: false

        /**
         * Starts [intent] from the accessibility service. Being bound by the system is one of the
         * routes that lets a background app start an activity, so this is the preferred launch
         * path for the car's quick launch panel while the service is on. False if it is off.
         */
        fun launch(intent: Intent): Boolean {
            val service = instance ?: return false
            return runCatching { service.startActivity(intent) }.isSuccess
        }
    }
}
