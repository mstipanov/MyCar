package com.example.mycar.auto

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mycar.MainActivity
import com.example.mycar.R
import com.example.mycar.Settings
import com.example.mycar.capture.ScreenMirrorService
import com.example.mycar.launch.AppCloser

/**
 * Watches Android Auto's connection state so mirroring follows the car, with no interaction.
 *
 * Android Auto publishes its connection state through a content provider and broadcasts
 * `CAR_CONNECTION_UPDATED` when it changes. Neither reaches an app that is not running, so this
 * has to be a (quiet) foreground service to survive between drives. On the transition to
 * "projecting" it launches [MainActivity] with [MainActivity.EXTRA_AUTO_START], which asks for
 * capture consent — but only once the phone is awake and unlocked, so the prompt is never parked
 * behind the keyguard (and never surfaces on a later unlock, after the car is gone). That launch
 * is only allowed from the background because the app holds "Display over other apps"
 * (`SYSTEM_ALERT_WINDOW`). On the way back it stops mirroring, so capture never keeps running
 * against a car that is no longer there.
 */
class CarConnectionWatcher : Service() {

    private var receiver: BroadcastReceiver? = null

    /** Receives the unlock or wake-up that lets a start requested meanwhile go ahead. */
    private var unlockReceiver: BroadcastReceiver? = null

    /** Edge-trigger state: only act when we go from not-connected to connected. */
    private var wasConnected = false

    /**
     * Android Auto connected while the phone was locked or asleep, so the launch waits for the
     * user to unlock it. Cleared once a launched screen reports in (see [markAutoStartDelivered])
     * or the car goes away.
     */
    private var startPending = false
    /** How many times the launch is tried before giving up on this connection. */
    private var startAttemptsLeft = 0

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Keeps trying while [startPending] is set. The unlock broadcasts are the fast path, but they
     * are not guaranteed (a locked phone may not deliver them, the keyguard can still count as
     * locked at [Intent.ACTION_USER_PRESENT], and the activity start itself can be dropped while
     * the phone wakes up), so the wait is also polled until the screen reports back.
     */
    private val pendingStartCheck = object : Runnable {
        override fun run() {
            if (!startPending) return
            startMirroringIfReady()
            if (startPending) handler.postDelayed(this, START_RETRY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = queryConnection()
        }
        // The broadcast comes from Android Auto, a different app, so the receiver must be exported.
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_CAR_CONNECTION_UPDATED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Starting the capture prompt needs an awake, unlocked phone; these two system broadcasts
        // say when that becomes true. Only the system sends them, so this receiver is not exported.
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Android Auto's state is only acted on once the phone is unlocked, which is also
                // when a rising edge missed while locked can be caught up on.
                if (readyForConsent(this@CarConnectionWatcher)) queryConnection()
                startMirroringIfReady()
            }
        }
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        queryConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(pendingStartCheck)
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        super.onDestroy()
    }

    private fun queryConnection() {
        onConnectionState(connectionState(this))
    }

    private fun onConnectionState(state: Int) {
        val connected = state == CONNECTION_PROJECTION
        val was = wasConnected
        wasConnected = connected
        when {
            connected && !was -> startMirroring()
            !connected && was -> onDisconnected()
        }
    }

    /** Rising edge: the car started projecting, so bring mirroring up by itself. */
    private fun startMirroring() {
        if (ScreenMirrorService.isRunning) return
        if (!Settings.autoStart(this)) return

        // Without this the activity start is silently blocked (Android 10+ background launch rule).
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Android Auto connected, but \"Display over other apps\" is not granted")
            return
        }
        // While the phone is locked or asleep the capture prompt cannot be answered: launching
        // then would only park it behind the keyguard, to surface whenever the user unlocks —
        // possibly long after the car is gone. Wait until it is awake and unlocked instead, and
        // keep checking: the unlock broadcast is not guaranteed to arrive.
        autoStartDelivered = false
        startAttemptsLeft = START_ATTEMPTS
        startPending = true
        handler.removeCallbacks(pendingStartCheck)
        startMirroringIfReady()
        if (startPending) handler.postDelayed(pendingStartCheck, START_RETRY_MS)
    }

    /** Launches the capture screen once the phone is awake and unlocked. */
    private fun startMirroringIfReady() {
        if (!startPending) return
        // The screen took over the start (it is asking for capture, or found the car gone and
        // went away); re-launching it would be pointless.
        if (autoStartDelivered) {
            startPending = false
            return
        }
        if (!readyForConsent(this)) return
        // The car may have left while the phone was locked; Android Auto's state is only trusted
        // once the phone is unlocked.
        if (!isProjecting(this)) {
            startPending = false
            return
        }
        if (startAttemptsLeft <= 0) {
            startPending = false
            return
        }
        startAttemptsLeft--
        Log.i(TAG, "Android Auto connected; starting mirroring")
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Falling edge: the car stopped projecting. Stop mirroring, and close the app the user chose,
     * if any. Close runs regardless of whether mirroring was up, so it still works when the
     * capture never started.
     */
    private fun onDisconnected() {
        startPending = false
        handler.removeCallbacks(pendingStartCheck)
        stopMirroring()
        // The phone UI does not need to stay in front once the car is gone; send it to the back.
        MainActivity.moveToBackIfShowing()
        closeChosenApp()
    }

    private fun closeChosenApp() {
        if (!Settings.closeAppOnDisconnect(this)) return
        val packageName = Settings.closeAppPackage(this)
        if (packageName.isBlank()) return
        Log.i(TAG, "Android Auto disconnected; closing $packageName")
        AppCloser.closeChosenApp(this)
    }

    /**
     * Capture left running would keep mirroring into a car that is gone (and keep the screen awake
     * and landscape-locked), so stop it. Auto-start covers the next connection; Android asks for
     * capture consent again when it comes back.
     */
    private fun stopMirroring() {
        if (!ScreenMirrorService.isRunning) return
        Log.i(TAG, "Android Auto disconnected; stopping mirroring")
        ScreenMirrorService.stop(this)
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.watcher_notification_channel),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(R.string.watcher_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "CarConnectionWatcher"
        private const val CHANNEL_ID = "car_connection"
        private const val NOTIFICATION_ID = 42

        /** How often a start waiting for the unlock is re-checked. */
        private const val START_RETRY_MS = 2_000L

        /** How many launches to try before leaving the start to the user. */
        private const val START_ATTEMPTS = 10

        private const val ACTION_CAR_CONNECTION_UPDATED =
            "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        private const val COLUMN_STATE = "CarConnectionState"
        private const val CONNECTION_NOT_CONNECTED = 0
        private const val CONNECTION_PROJECTION = 2

        private val CONNECTION_URI: Uri =
            Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()

        /**
         * Android Auto's live connection state, or [CONNECTION_NOT_CONNECTED] when it cannot be
         * read. Shared with the mirror service, which uses it to decide whether a capture that the
         * system stopped on its own should be asked for again.
         */
        fun connectionState(context: Context): Int = runCatching {
            context.contentResolver
                .query(CONNECTION_URI, arrayOf(COLUMN_STATE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else CONNECTION_NOT_CONNECTED
                }
        }.getOrNull() ?: CONNECTION_NOT_CONNECTED

        /** Whether Android Auto is currently projecting, i.e. the car is showing us. */
        fun isProjecting(context: Context): Boolean =
            connectionState(context) == CONNECTION_PROJECTION

        /** Set by [MainActivity] once it has taken over an auto-start; same process as the service. */
        @Volatile
        private var autoStartDelivered = false

        /**
         * Called by [MainActivity] as soon as it actually handles an auto-start, so the watcher
         * can stop re-launching it. A start the system silently blocks looks just like a
         * successful one from here, hence the confirmation from the screen itself.
         */
        fun markAutoStartDelivered() {
            autoStartDelivered = true
        }

        /**
         * Whether the capture consent prompt could actually be shown and answered right now.
         * While the phone is locked or asleep the prompt has nowhere to go, so a start that
         * arrives then waits for [Intent.ACTION_USER_PRESENT] / [Intent.ACTION_SCREEN_ON].
         */
        private fun readyForConsent(context: Context): Boolean {
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            val interactive = power?.isInteractive ?: true
            val locked = keyguard?.isKeyguardLocked ?: false
            return interactive && !locked
        }

        /**
         * Whether the watcher should be running: auto-start needs "Display over other apps" for
         * its background activity start, closing an app on disconnect does not. Shared by the
         * phone UI and [RestartReceiver] so both re-arm it under the same conditions.
         */
        fun wanted(context: Context): Boolean =
            (Settings.autoStart(context) && android.provider.Settings.canDrawOverlays(context)) ||
                Settings.closeAppOnDisconnect(context)

        /** Idempotently starts the watcher. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CarConnectionWatcher::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarConnectionWatcher::class.java))
        }
    }
}
