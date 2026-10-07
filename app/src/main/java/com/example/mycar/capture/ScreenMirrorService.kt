package com.example.mycar.capture

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mycar.MainActivity
import com.example.mycar.R
import com.example.mycar.RotationLock
import com.example.mycar.Settings
import com.example.mycar.auto.CarConnectionWatcher

/**
 * Mirrors this device's own display into [FrameStore] as fast as the screen changes.
 *
 * This has to be a foreground service with a `mediaProjection` type: since Android 14 the
 * system refuses [MediaProjectionManager.getMediaProjection] unless such a service is
 * already running.
 *
 * A rotation changes the captured size, and a [VirtualDisplay]'s backing [ImageReader] has a
 * fixed size, so the capture has to follow. It cannot be rebuilt, though: since Android 14 a
 * [MediaProjection] may only ever create **one** virtual display
 * (`SecurityException: ... don't take multiple captures by invoking
 * MediaProjection#createVirtualDisplay multiple times on the same instance`). Instead the
 * existing display is re-pointed at a fresh, correctly sized reader with
 * [VirtualDisplay.setSurface] and [VirtualDisplay.resize]; see [resizeCapture].
 */
class ScreenMirrorService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null

    /** Size the current [virtualDisplay] and [imageReader] were created with. */
    private var captureSize: Point? = null

    /** True while a re-request for a capture the system stopped on its own is being waited on. */
    private var recoveryPending = false

    /** Held while mirroring if the user asked for the screen to stay on. */
    private var wakeLock: PowerManager.WakeLock? = null

    /** Lets a change to a setting take effect without restarting capture. */
    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Settings.KEY_KEEP_SCREEN_ON -> applyKeepScreenOn()
                Settings.KEY_LOCK_LANDSCAPE -> applyRotationLock()
            }
        }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture, or the system stopped it (screen off, device lock, a call).
            release(stoppedByProjection = true)
            stopSelf()
            scheduleRecovery()
        }

        /**
         * API 34+ tells us the captured content changed size (typically a rotation). API 34 also
         * forbids a second virtual display, so re-pointing the existing one is the only option.
         */
        override fun onCapturedContentResize(width: Int, height: Int) {
            resizeCapture(Point(width, height))
        }
    }

    /**
     * Backs up [projectionCallback]'s resize signal on older platforms, where
     * `onCapturedContentResize` does not exist. On API 34+ it stays out of the way so the two
     * cannot fight over the size.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            resizeCapture(realDisplaySize())
        }
    }

    override fun onCreate() {
        super.onCreate()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(settingsListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithType(NOTIFICATION_ID, buildNotification())

        if (projection == null) {
            try {
                startCapture(resultCode, resultData)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start capture", t)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        release(stoppedByProjection = false)
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
            ?: run {
                stopSelf()
                return
            }
        projection = mediaProjection

        // Required before createVirtualDisplay() on API 34+.
        mediaProjection.registerCallback(projectionCallback, Handler(mainLooper))

        captureThread = HandlerThread("screen-mirror-capture").also { it.start() }

        buildCapture(mediaProjection, realDisplaySize())

        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, Handler(mainLooper))

        isRunning = true
        applyKeepScreenOn()
        applyRotationLock()
        openStartApp()
    }

    /**
     * Opens the user's chosen app once mirroring is live, so the car mirror settles on it instead
     * of the phone's home screen. The launch is delayed slightly to let the capture prompt finish.
     * Firing from the background (auto-start) needs "Display over other apps".
     */
    private fun openStartApp() {
        val packageName = Settings.startAppPackage(this)
        if (packageName.isBlank()) return
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Handler(mainLooper).postDelayed({
            runCatching { startActivity(launch) }
                .onFailure { Log.w(TAG, "Could not open $packageName", it) }
        }, START_APP_DELAY_MS)
    }

    /**
     * Capture was ended by the system rather than by the user: the screen locked, the phone went
     * to sleep, or a call came in. Mirroring is meant to follow the car, so ask for capture again
     * and let [openStartApp] reopen the configured app. A phone call usually stops capture while
     * the screen is off or the phone is locked, when the consent prompt could not be answered, so
     * keep retrying until the phone is awake and unlocked. Give up if Android Auto goes away
     * first, or after [RECOVERY_ATTEMPTS] tries.
     */
    private fun scheduleRecovery() {
        if (recoveryPending) return
        val appContext = applicationContext
        if (!CarConnectionWatcher.isProjecting(appContext)) return
        // Needed to bring the consent screen up while MyCar is in the background.
        if (!android.provider.Settings.canDrawOverlays(appContext)) return

        recoveryPending = true
        val handler = Handler(appContext.mainLooper)
        val attempt = object : Runnable {
            private var attemptsLeft = RECOVERY_ATTEMPTS

            override fun run() {
                val stillStopped = !ScreenMirrorService.isRunning
                if (!stillStopped || !CarConnectionWatcher.isProjecting(appContext)) {
                    recoveryPending = false
                    return
                }
                if (readyForConsent(appContext)) {
                    recoveryPending = false
                    Log.i(TAG, "Capture stopped under Android Auto; asking for it again")
                    runCatching {
                        appContext.startActivity(
                            Intent(appContext, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { Log.w(TAG, "Could not reopen the capture prompt", it) }
                    return
                }
                if (--attemptsLeft <= 0) {
                    recoveryPending = false
                    return
                }
                handler.postDelayed(this, RECOVERY_RETRY_MS)
            }
        }
        handler.postDelayed(attempt, RECOVERY_RETRY_MS)
    }

    /** Whether the capture consent prompt could actually be shown and answered right now. */
    private fun readyForConsent(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val interactive = power?.isInteractive ?: true
        val locked = keyguard?.isKeyguardLocked ?: false
        return interactive && !locked
    }

    /** Creates the initial reader + virtual display pair. Only ever called once per projection. */
    private fun buildCapture(mediaProjection: MediaProjection, size: Point) {
        val thread = captureThread ?: return
        val densityDpi = resources.displayMetrics.densityDpi

        val reader = ImageReader.newInstance(
            size.x,
            size.y,
            PixelFormat.RGBA_8888,
            IMAGE_BUFFER_COUNT
        )
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, Handler(thread.looper))

        val display = mediaProjection.createVirtualDisplay(
            "mycar-mirror",
            size.x,
            size.y,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        imageReader = reader
        virtualDisplay = display
        captureSize = size
    }

    /**
     * Re-points the running capture at [size]. Reuses the existing [virtualDisplay], which is
     * mandatory on Android 14+; only the backing [ImageReader] is replaced.
     */
    private fun resizeCapture(size: Point) {
        val display = virtualDisplay ?: return
        val thread = captureThread ?: return
        if (size.x <= 0 || size.y <= 0) return

        val current = captureSize
        if (current != null && current.x == size.x && current.y == size.y) return

        val densityDpi = resources.displayMetrics.densityDpi
        val newReader = ImageReader.newInstance(
            size.x,
            size.y,
            PixelFormat.RGBA_8888,
            IMAGE_BUFFER_COUNT
        )
        newReader.setOnImageAvailableListener({ onImageAvailable(it) }, Handler(thread.looper))

        try {
            // Adopt the new surface first, then the matching size, so the display never renders
            // a new size into a surface that cannot hold it.
            display.setSurface(newReader.surface)
            display.resize(size.x, size.y, densityDpi)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not resize capture to ${size.x}x${size.y}", t)
            newReader.close()
            return
        }

        val oldReader = imageReader
        imageReader = newReader
        captureSize = size

        // Detach the old reader and close it on the capture thread. Posting to that same thread
        // guarantees any in-flight frame callback finishes first: closing an ImageReader from
        // under a running acquireLatestImage() crashes natively (SIGSEGV).
        oldReader?.setOnImageAvailableListener(null, null)
        Handler(thread.looper).post { oldReader?.close() }

        // Drop the frame captured at the old size so it cannot be drawn, wrongly shaped, first.
        FrameStore.clear()
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return

        try {
            val plane = image.planes.getOrNull(0) ?: return
            FrameStore.update(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride
            )
        } catch (t: Throwable) {
            // A single malformed or oversized frame must never kill the capture loop.
            Log.w(TAG, "Dropped a frame", t)
        } finally {
            image.close()
        }
    }

    private fun releaseCaptureResources() {
        virtualDisplay?.release()
        virtualDisplay = null

        val reader = imageReader
        imageReader = null
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null)
            val looper = captureThread?.looper
            if (looper != null) Handler(looper).post { reader.close() } else reader.close()
        }

        captureSize = null

        FrameStore.clear()
    }

    private fun release(stoppedByProjection: Boolean) {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)

        releaseCaptureResources()

        // Processes the pending close from releaseCaptureResources() before shutting down.
        captureThread?.quitSafely()
        captureThread = null

        val mediaProjection = projection
        projection = null
        if (mediaProjection != null) {
            mediaProjection.unregisterCallback(projectionCallback)
            if (!stoppedByProjection) {
                mediaProjection.stop()
            }
        }

        isRunning = false
        applyKeepScreenOn()
        applyRotationLock()
    }

    /**
     * Forces landscape for the duration of a capture when the user asked for it, and puts the
     * phone's normal auto-rotate behaviour back afterwards.
     */
    private fun applyRotationLock() {
        if (isRunning && Settings.lockLandscape(this)) {
            RotationLock.lockLandscape(this)
        } else {
            RotationLock.unlock(this)
        }
    }

    /**
     * Keeps the phone screen awake while a capture is live, when the user asked for it. Uses a
     * `SCREEN_BRIGHT_WAKE_LOCK`, which is deprecated but is still the only way for a service to
     * keep the display on without owning a visible window.
     */
    private fun applyKeepScreenOn() {
        val wanted = isRunning && Settings.keepScreenOn(this)
        val held = wakeLock
        if (wanted && held == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val lock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "MyCar:keepScreenOn"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        } else if (!wanted && held != null) {
            if (held.isHeld) held.release()
            wakeLock = null
        }
    }

    private fun realDisplaySize(): Point {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val point = Point()
        if (display != null) {
            // Reflects the current rotation, so it changes when the phone is turned.
            @Suppress("DEPRECATION")
            display.getRealSize(point)
        }
        return point
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.mirror_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.mirror_notification_title))
            .setContentText(getString(R.string.mirror_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** `Service.startForeground` only accepts a type on API 29+, so branch and call it directly. */
    private fun startForegroundWithType(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(id, notification)
        }
    }

    companion object {
        private const val TAG = "ScreenMirrorService"
        private const val CHANNEL_ID = "screen_mirror"
        private const val NOTIFICATION_ID = 1
        private const val IMAGE_BUFFER_COUNT = 2

        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** Grace period before opening the chosen app, so the capture prompt can close first. */
        private const val START_APP_DELAY_MS = 800L

        /** Delay before a recovery attempt, and between attempts, after the system stops capture. */
        private const val RECOVERY_RETRY_MS = 2_000L

        /** How often recovery waits for the phone to wake and unlock before giving up (~10 min). */
        private const val RECOVERY_ATTEMPTS = 300

        /** Whether a capture is currently live. Read by the phone UI. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Starts capture from a granted [MediaProjectionManager.createScreenCaptureIntent] result. */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenMirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenMirrorService::class.java))
        }
    }
}
