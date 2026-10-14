package com.example.mycar.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.mycar.Settings

/**
 * Accessibility service with three jobs:
 *
 * - Inject taps the user makes on the car mirror, so the car touchscreen can drive the phone
 *   (`canPerformGestures`).
 * - Press Share on the system screen-capture prompt for hands-free start
 *   (`canRetrieveWindowContent`).
 * - Close the app chosen for "close when Android Auto disconnects": leave it (Home) or force stop
 *   it by driving the app-info screen (`canRetrieveWindowContent`).
 *
 * A normal app cannot synthesize input for other apps; an accessibility service with gestures is
 * one of the two supported routes (root is the other). This service acts only when
 * [MirrorSurfaceCallback] forwards a tap, on SystemUI's projection window, or while a force-stop
 * request started by [forceStop] is pending. It never reads or touches anything else. The user
 * enables it manually in Settings -> Accessibility.
 */
class TouchInjectorService : AccessibilityService() {

    /** Debounce so the capture prompt is accepted once, not on every window change. */
    private var lastAutoAcceptAt = 0L

    /** Package a [forceStop] is currently driving the app-info screen for, or null. */
    private var forceStopPackage: String? = null

    /** Wall clock after which a pending [forceStop] gives up. */
    private var forceStopDeadline = 0L

    /** 0 = idle, 1 = looking for the Force stop button, 2 = confirming the dialog. */
    private var forceStopPhase = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        autoAcceptCapturePrompt(event)
        driveForceStop(event)
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

    /**
     * Drives the app-info screen's "Force stop" for a pending [forceStop] request: first the
     * button on the app-info page, then the confirmation dialog. Strictly scoped — it does
     * nothing unless [forceStop] asked for it, only reads Settings' own window, and gives up after
     * [FORCE_STOP_TIMEOUT_MS] so a screen we never reach (a locked phone, an OEM that renamed or
     * moved the button) cannot leave the service acting on unrelated windows.
     */
    private fun driveForceStop(event: AccessibilityEvent?) {
        val packageName = forceStopPackage ?: return
        if (SystemClock.uptimeMillis() > forceStopDeadline) {
            Log.w(TAG, "giving up force stopping $packageName")
            resetForceStop()
            return
        }
        if (event?.packageName?.toString() != SETTINGS_PACKAGE) return
        val root = rootInActiveWindow ?: return
        when (forceStopPhase) {
            1 -> {
                val button = findForceStopButton(root) ?: return
                if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "pressed Force stop for $packageName")
                    forceStopPhase = 2
                }
            }
            2 -> {
                val confirm = root.findAccessibilityNodeInfosByViewId(CONFIRM_BUTTON_ID).firstOrNull()
                    ?: return
                if (confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "force stopped $packageName")
                    resetForceStop()
                    // Leave the app-info screen once the app is gone.
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    /** The Force stop button on Settings' app-info page: by id on AOSP, by label elsewhere. */
    private fun findForceStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$SETTINGS_PACKAGE:id/force_stop_button")
            .firstOrNull { it.isClickable }
            ?.let { return it }
        for (label in FORCE_STOP_LABELS) {
            root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.isClickable }
                ?.let { return it }
        }
        return null
    }

    private fun resetForceStop() {
        forceStopPackage = null
        forceStopPhase = 0
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        resetForceStop()
        Log.i(TAG, "disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        resetForceStop()
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

    private fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        onComplete: (() -> Unit)?,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(10L, 2_000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (onComplete == null) return dispatchGesture(gesture, null, null)
        // Report on both outcomes: the caller serializes pans off this callback, so a cancelled
        // gesture must release it too rather than stalling the next pan forever.
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onComplete()
            override fun onCancelled(gestureDescription: GestureDescription?) = onComplete()
        }
        return dispatchGesture(gesture, callback, null)
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

        /** Settings' app-info screen and the "Force stop" labels this service looks for. */
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val CONFIRM_BUTTON_ID = "android:id/button1"
        private val FORCE_STOP_LABELS = listOf("Force stop", "Zaustavi")

        /** How long a force-stop request waits for the app-info screen before giving up. */
        private const val FORCE_STOP_TIMEOUT_MS = 8_000L

        @Volatile
        private var instance: TouchInjectorService? = null

        /** Whether the user has switched the service on in Settings. */
        val isConnected: Boolean get() = instance != null

        /** Injects a tap at the given phone pixel. Returns false if the service is not enabled. */
        fun tap(x: Float, y: Float): Boolean = instance?.tap(x, y) ?: false

        /**
         * Injects a swipe between two phone pixels, calling [onComplete] when it finishes or is
         * cancelled. Returns false when the service is off — in which case [onComplete] is never
         * called, so callers must not rely on it to make progress.
         */
        fun swipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
            onComplete: (() -> Unit)? = null,
        ): Boolean = instance?.swipe(x1, y1, x2, y2, durationMs, onComplete) ?: false

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

        /** The package of the window in front, or null when the service is off. */
        fun foregroundPackage(): String? =
            instance?.rootInActiveWindow?.packageName?.toString()

        /** Sends the current foreground app to the background (goes home). False if the service
         * is off. */
        fun goHome(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true

        /**
         * Force stops [packageName] by opening its app-info screen and pressing "Force stop". The
         * page is driven from [onAccessibilityEvent]. False when the service is off or the screen
         * could not be opened; the caller should fall back to leaving the app.
         */
        fun forceStop(packageName: String): Boolean = instance?.startForceStop(packageName) == true
    }

    /** Opens the app-info screen and arms [driveForceStop] to press Force stop on it. */
    private fun startForceStop(packageName: String): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open app info for $packageName", it) }
            .isSuccess
        if (!started) return false
        forceStopPackage = packageName
        forceStopDeadline = SystemClock.uptimeMillis() + FORCE_STOP_TIMEOUT_MS
        forceStopPhase = 1
        return true
    }
}
